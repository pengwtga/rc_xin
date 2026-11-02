#!/usr/bin/env bash
# T3.3 指标 / 背压 / 归档的现场演示：
#   1) 提交三条通知：1 条成功、1 条持续 502（进 RETRY_WAIT）、1 条预算压到 1（进 DEAD）
#   2) /actuator/prometheus 抓核心指标（受理 QPS/错误率、队列深度、最老逾期、投递结果、死信、熔断）
#   3) 队列积压超过阈值 → 新提交 503 + Retry-After，且不落库
#   4) 归档任务跑一轮：成功单的 body/headers 密文变 NULL、updated_at 不变；DEAD 单保留
#
# 为了让演示等得起，启动参数把采样压到 2s、归档 cron 压到每 5s、成功保留期设 0 小时、
# 积压阈值压到 1 条。生产默认值见 application.yml（采样 10s / 每日 3:30 / 24h 与 7d / 10 万条）。
#
# 背压段不用"真实失败流量"制造积压，而是直接插两条 next_attempt_at 在未来的 PENDING：
#   ① 未来时刻不会被认领，积压量稳定，不受投递时序影响（用真实流量演示会因"恰好正在重试"而抖动）；
#   ② backlog 的口径是 PENDING + RETRY_WAIT、不看时间，这正是背压要防的"收下了但投不出去"。
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18085
MOCK_OK_PORT=9105
MOCK_FAIL_PORT=9106
LOG_APP=/tmp/t33_app.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 notify"
BASE="http://127.0.0.1:${APP_PORT}"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${MOCK_OK_PID:-}" ] && kill "$MOCK_OK_PID" 2>/dev/null
  [ -n "${MOCK_FAIL_PID:-}" ] && kill "$MOCK_FAIL_PID" 2>/dev/null
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

submit() {
  curl -s --noproxy '*' -X POST "${BASE}/v1/notifications" \
    -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d "{
      \"idempotency_key\": \"$1\",
      \"target_url\": \"$2\",
      \"headers\": {\"Authorization\": \"Bearer supplier-token\"},
      \"body\": \"{\\\"phone\\\":\\\"13800001111\\\"}\"
    }" | sed -n 's/.*"notification_no":\([0-9]*\).*/\1/p'
}

python3 scripts/mock_downstream.py --port "$MOCK_OK_PORT" --status 200 >/tmp/t33_mock_ok.log 2>&1 &
MOCK_OK_PID=$!
python3 scripts/mock_downstream.py --port "$MOCK_FAIL_PORT" --status 502 >/tmp/t33_mock_fail.log 2>&1 &
MOCK_FAIL_PID=$!

NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java -jar target/rc-xin.jar \
  --server.port="$APP_PORT" \
  --notify.metrics.sample-interval-ms=2000 \
  --notify.archive.cron="*/5 * * * * *" \
  --notify.archive.success-retention-hours=0 \
  --notify.backpressure.max-pending=1 \
  --notify.backoff.base-ms=1000 >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "${BASE}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
if ! curl -s --noproxy '*' "${BASE}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
  echo "应用未就绪，日志尾部："
  tail -20 "$LOG_APP"
  exit 1
fi
echo "应用已就绪（:${APP_PORT}）；下游 200（:${MOCK_OK_PORT}）与恒 502（:${MOCK_FAIL_PORT}）"

$MYSQL -e "DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null
# 背压读的是采样快照（演示 2s）：清库后要等一次采样，否则它还拿着上一轮的积压量
sleep 3

say "1. 提交三条：成功 / 重试中 / 死信"
OK_NO=$(submit "order_paid:t33_ok" "http://127.0.0.1:${MOCK_OK_PORT}/cb")
sleep 2
F1_NO=$(submit "order_paid:t33_f1" "http://127.0.0.1:${MOCK_FAIL_PORT}/cb")
sleep 2
F2_NO=$(submit "order_paid:t33_f2" "http://127.0.0.1:${MOCK_FAIL_PORT}/cb")
# 演示用：预算压到 1，一次 502 就进 DEAD（生产默认 10 次、累计约 3.5 小时）
$MYSQL -e "UPDATE notification SET max_attempts = 1 WHERE id = ${F2_NO};" 2>/dev/null
sleep 2
echo "→ 成功单 ${OK_NO}，重试单 ${F1_NO}，死信单 ${F2_NO}（预算压到 1）"

say "2. 状态落库结果"
$MYSQL -e "SELECT id, status, attempt_count, last_status_code, last_error_class FROM notification ORDER BY id;" 2>/dev/null

say "3. /actuator/prometheus 里的核心指标"
curl -s --noproxy '*' "${BASE}/actuator/prometheus" | grep -E "^notify_" \
  | sed -e 's/{application="rc-notify",/{/' -e 's/{application="rc-notify"}/ /' | sort

say "4. 背压：造 2 条积压（阈值 1）→ 新提交 503 + Retry-After，且不落库"
$MYSQL -e "
  INSERT INTO notification (caller_id, idempotency_key, target_url, http_method,
                            headers_enc, body_enc, status, attempt_count, max_attempts, next_attempt_at)
  VALUES ('shop', 'bp:t33:1', 'http://127.0.0.1:${MOCK_OK_PORT}/cb', 'POST',
          0x00, 0x00, 'PENDING', 0, 10, DATE_ADD(NOW(3), INTERVAL 1 HOUR)),
         ('shop', 'bp:t33:2', 'http://127.0.0.1:${MOCK_OK_PORT}/cb', 'POST',
          0x00, 0x00, 'PENDING', 0, 10, DATE_ADD(NOW(3), INTERVAL 1 HOUR));" 2>/dev/null
echo "→ 两条 PENDING 的 next_attempt_at 在未来，不会被认领，积压量稳定"
echo "  （0x00 只是占位字节：这两行永远不投递，演示只需要区分密文的 NULL / 非 NULL）"
sleep 3
BEFORE=$($MYSQL -N -B -e "SELECT COUNT(*) FROM notification;" 2>/dev/null)
curl -s --noproxy '*' -i -X POST "${BASE}/v1/notifications" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d "{
    \"idempotency_key\": \"order_paid:t33_blocked\",
    \"target_url\": \"http://127.0.0.1:${MOCK_OK_PORT}/cb\"
  }" | sed -n '1p;/^Retry-After/p;/^{/p'
AFTER=$($MYSQL -N -B -e "SELECT COUNT(*) FROM notification;" 2>/dev/null)
echo "→ 表内行数：提交前 ${BEFORE}，提交后 ${AFTER}（背压在受理前拒绝，不落库）"

say "5. 归档：等一轮 cron（每 5s），成功单的密文变 NULL、updated_at 不变"
OK_UPDATED_BEFORE=$($MYSQL -N -B -e "SELECT updated_at FROM notification WHERE id = ${OK_NO};" 2>/dev/null)
sleep 7
$MYSQL -e "
  SELECT id, status, body_enc IS NULL AS body_cleared, headers_enc IS NULL AS headers_cleared
  FROM notification ORDER BY id;" 2>/dev/null
OK_UPDATED_AFTER=$($MYSQL -N -B -e "SELECT updated_at FROM notification WHERE id = ${OK_NO};" 2>/dev/null)
echo "→ 成功单 ${OK_NO} 的 updated_at：归档前 ${OK_UPDATED_BEFORE} / 归档后 ${OK_UPDATED_AFTER}"
echo "→ SUCCEEDED 行密文已清空；DEAD 行仍在 7 天保留期内，密文保留"

say "6. 归档、死信与背压指标"
curl -s --noproxy '*' "${BASE}/actuator/prometheus" \
  | grep -E "^notify_(archive|deadletter|ingest_backpressure)" \
  | sed -e 's/{application="rc-notify",/{/' -e 's/{application="rc-notify"}/ /'

echo
echo "演示结束"
