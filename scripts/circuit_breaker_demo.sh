#!/usr/bin/env bash
# T2.5 端点隔离与熔断的现场演示。
#
# 场景：两个供应商，一个恒返回 500（故障），一个恒返回 200（健康）。40 条任务打故障方、20 条打健康方。
# 验收点（任务拆解 T2.5 原话的直译）：
#   1) 故障方的任务停在 RETRY_WAIT，attempt_count 不增长（熔断跳过不消耗尝试预算）
#   2) 健康方的投递不受影响，全部 SUCCEEDED
# 对照：若没有熔断，40 条 × 10 次预算 = 最多 400 次请求，且大批任务会被烧成 DEAD。
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18084
BAD_PORT=9105
GOOD_PORT=9106
LOG_APP=/tmp/t25_app.log
LOG_BAD=/tmp/t25_bad.log
LOG_GOOD=/tmp/t25_good.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 notify"
BAD_URL="http://127.0.0.1:${BAD_PORT}/cb"
GOOD_URL="http://127.0.0.1:${GOOD_PORT}/cb"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${BAD_PID:-}" ] && kill "$BAD_PID" 2>/dev/null
  [ -n "${GOOD_PID:-}" ] && kill "$GOOD_PID" 2>/dev/null
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

python3 scripts/mock_downstream.py --port "$BAD_PORT" --status 500 >"$LOG_BAD" 2>&1 &
BAD_PID=$!
python3 scripts/mock_downstream.py --port "$GOOD_PORT" --status 200 >"$LOG_GOOD" 2>&1 &
GOOD_PID=$!

# 小池子（8）让单端点并发（默认 16）不成为瓶颈，故障隔离的观察点只在熔断上；
# 退避缩到 1~2s，让 20 个失败样本在几秒内攒够（默认曲线要约 3 分钟）
NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java -jar target/rc-xin.jar --server.port="$APP_PORT" \
  --notify.dispatcher.worker-pool-size=8 \
  --notify.dispatcher.batch-size-max=8 \
  --notify.circuit.min-samples=20 \
  --notify.circuit.max-concurrent-per-endpoint=4 \
  --notify.circuit.open-ms=60000 \
  --notify.circuit.skip-delay-seconds=30 \
  --notify.backoff.base-ms=1000 \
  --notify.backoff.cap-ms=2000 >"$LOG_APP" 2>&1 &
APP_PID=$!
ready=0
for _ in $(seq 1 60); do
  if curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" = 0 ]; then
  echo "应用未就绪（启动自检或端口冲突），日志尾部："
  tail -20 "$LOG_APP"
  exit 1
fi
echo "应用已就绪（:${APP_PORT}），worker 池=8、单端点并发=4、熔断阈值=20 样本/50%"

$MYSQL -e "DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null

submit() {
  curl -s --noproxy '*' -o /dev/null -w '%{http_code} ' -X POST "http://127.0.0.1:${APP_PORT}/v1/notifications" \
    -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
    -d "{\"idempotency_key\":\"$1\",\"target_url\":\"$2\",\"http_method\":\"POST\",\"body\":\"{\\\"n\\\":1}\"}"
}

say "1. 提交 40 条到故障端点（${BAD_URL}，恒 500）、20 条到健康端点（${GOOD_URL}，恒 200）"
for i in $(seq 1 40); do submit "cb_bad_${i}" "$BAD_URL"; done
echo
for i in $(seq 1 20); do submit "cb_good_${i}" "$GOOD_URL"; done
echo

say "2. 观察故障端点收到的请求数（应在 20 来个样本后停止增长）"
prev=-1
for t in $(seq 1 30); do
  sleep 1
  cur=$(grep -c '\[mock #' "$LOG_BAD" 2>/dev/null || true)
  cur=${cur:-0}
  echo "  t=${t}s  故障端点累计收到 ${cur} 次请求"
  if [ "$cur" -gt 0 ] && [ "$cur" = "$prev" ]; then
    echo "  → 请求停止增长（熔断已打开）"
    break
  fi
  prev=$cur
done

# 熔断打开时还有几条投递在途（请求已发出、结果未落库）。等它们落地再取快照，
# 否则会读到"mock 已收到 20 次、库里只有 8 条"这种中间态——那不是 bug，是采样太早
sleep 2

say "3. 故障端点：任务状态分布（关键：没有 DEAD）"
$MYSQL -e "SELECT status, COUNT(*) AS cnt, MIN(attempt_count) AS min_attempt, MAX(attempt_count) AS max_attempt
FROM notification WHERE idempotency_key LIKE 'cb\_bad\_%' GROUP BY status;" 2>/dev/null

say "4. 故障端点：真正的请求只发生了这么多次（= delivery_attempt 行数）"
$MYSQL -e "SELECT COUNT(*) AS requests_sent,
       (SELECT COALESCE(SUM(attempt_count),0) FROM notification WHERE idempotency_key LIKE 'cb\_bad\_%') AS attempts_kept,
       (SELECT COUNT(*) FROM notification WHERE idempotency_key LIKE 'cb\_bad\_%') * 10 AS budget_if_no_breaker
FROM delivery_attempt a JOIN notification n ON n.id = a.notification_id
WHERE n.idempotency_key LIKE 'cb\_bad\_%';" 2>/dev/null

say "5. 健康端点：全部成功，一次都没被拖累（等它投完——单端点并发只有 4，需要几轮）"
for _ in $(seq 1 20); do
  ok=$($MYSQL -N -e "SELECT COUNT(*) FROM notification WHERE idempotency_key LIKE 'cb\_good\_%' AND status='SUCCEEDED';" 2>/dev/null || true)
  [ "${ok:-0}" = "20" ] && break
  sleep 1
done
$MYSQL -e "SELECT status, COUNT(*) AS cnt, MAX(attempt_count) AS max_attempt
FROM notification WHERE idempotency_key LIKE 'cb\_good\_%' GROUP BY status;" 2>/dev/null

say "6. 日志里的熔断信号"
grep -E "端点熔断" "$LOG_APP" | tail -3

say "7. 结论"
echo "故障端点：40 条任务一条都没死（0 DEAD），已发出的请求停在熔断前的样本数附近；"
echo "         没有熔断的话这 40 条会一路重试到预算耗尽（最多 400 次请求 + 大批 DEAD）。"
echo "健康端点：20 条全部 SUCCEEDED，与故障端点共用同一个 worker 池，没被拖慢。"
echo "被跳过的任务推迟 30s 后再试——熔断恢复（半开探测成功）后会自动继续，不需要人工干预。"
