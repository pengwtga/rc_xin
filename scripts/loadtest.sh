#!/usr/bin/env bash
# T4.2 压测：150 QPS 打受理接口 60 秒，记录受理 p99 / DB 写入速率 / worker 利用率。
#
# 三个数分别回答三个问题：
#   受理 p99      —— 设计文档 §9.1 的验收线（<50ms）：同步路径上没有隐藏的阻塞；
#   DB 写入速率   —— §7 的容量估算（~350 行/秒，单机余量 ~9 倍）是不是空话；
#   worker 利用率 —— worker-pool-size=200 是按"下游整体慢到 1s"取的，正常态应该远低于满。
#
# 压测器是自己写的 Java 单文件（scripts/loadgen/LoadGen.java），原因见那边注释：
# ab 无法给每个请求不同的幂等键，k6/wrk 需要额外安装。
#
# 用法： bash scripts/loadtest.sh [--rate 150] [--duration 60]
set -u
cd "$(dirname "$0")/.." || exit 1

RATE=150
DURATION=60
WARMUP=10
MOCK_SLEEP=0
ENDPOINTS=12
APP_ARGS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --rate) RATE="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --mock-sleep) MOCK_SLEEP="$2"; shift 2 ;;
    --endpoints) ENDPOINTS="$2"; shift 2 ;;
    --app-args) APP_ARGS="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

APP_PORT=18088
MOCK_PORT=9108
LOG_APP=/tmp/t42_app.log
LOG_LOAD=/tmp/t42_load.txt
LOG_SAMPLE=/tmp/t42_sample.txt
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 -N -B notify"
BASE="http://127.0.0.1:${APP_PORT}"

[ -f target/rc-xin.jar ] || { echo "先打包: mvn -DskipTests package"; exit 1; }

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  for pid in ${MOCK_PIDS:-}; do kill "$pid" 2>/dev/null; done
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

say "0. 准备：清库 + 起 ${ENDPOINTS} 个 mock 下游（--quiet，避免它的 stdout 成为瓶颈）"
$MYSQL -e "DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null
MOCK_PIDS=""
TARGETS=""
for i in $(seq 0 $((ENDPOINTS - 1))); do
  port=$((MOCK_PORT + i))
  python3 scripts/mock_downstream.py --port "$port" --status 200 --sleep "$MOCK_SLEEP" --quiet \
    >"/tmp/t42_mock_${port}.log" 2>&1 &
  MOCK_PIDS="$MOCK_PIDS $!"
  TARGETS="${TARGETS}${TARGETS:+,}http://127.0.0.1:${port}/cb"
done

# 压测跑的是"生产默认参数"：worker 200、批 100、采样 10s、背压 10 万——不调参才叫实测。
# --app-args 只用于诊断实验（例如临时关掉采样器，看它是不是 p99 的来源）
NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java ${JAVA_OPTS:-} -jar target/rc-xin.jar --server.port="$APP_PORT" $APP_ARGS \
  >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "${BASE}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
if ! curl -s --noproxy '*' "${BASE}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
  echo "应用未就绪，最后 20 行日志："; tail -20 "$LOG_APP"; exit 1
fi

metric() {  # 取单个 Gauge 的值（去掉标签与时间戳）
  curl -s --noproxy '*' "${BASE}/actuator/prometheus" | awk -v name="$1" '$1 ~ "^"name"({|$)" {print $NF; exit}'
}
mysql_status() {  # MySQL 全局计数器
  $MYSQL -e "SHOW GLOBAL STATUS LIKE '$1';" 2>/dev/null | awk '{print $2}'
}

BEFORE_NOTIF=$($MYSQL -e "SELECT COUNT(*) FROM notification;" 2>/dev/null | tr -d ' ')
BEFORE_ROWS_INS=$(mysql_status Innodb_rows_inserted)
BEFORE_ROWS_UPD=$(mysql_status Innodb_rows_updated)

say "1. 在采样的同时压测：${RATE} QPS × ${DURATION}s（预热 ${WARMUP}s 不计入统计，下游延迟 ${MOCK_SLEEP}s）"
: >"$LOG_SAMPLE"
STOP=/tmp/t42_stop
rm -f "$STOP"
(
  while [ ! -f "$STOP" ]; do
    printf '%s %s %s\n' "$(date +%s)" "$(metric notify_dispatch_workers_active)" "$(metric notify_queue_backlog)" >>"$LOG_SAMPLE"
    sleep 2
  done
) &
SAMPLER_PID=$!

java scripts/loadgen/LoadGen.java --rate "$RATE" --duration "$DURATION" --warmup "$WARMUP" \
  --url "${BASE}/v1/notifications" --key k_shop \
  --target "$TARGETS" >"$LOG_LOAD" 2>&1
touch "$STOP"
wait "$SAMPLER_PID" 2>/dev/null

SUMMARY=$(grep '^SUMMARY' "$LOG_LOAD")
sed -n '/压测结果/,/SUMMARY/p' "$LOG_LOAD" | sed '$d'

say "2. 等队列排空（最多 60s）"
for _ in $(seq 1 60); do
  BACKLOG=$(metric notify_queue_backlog)
  [ "${BACKLOG:-1}" = "0" ] && break
  sleep 1
done
echo "排空后 backlog = ${BACKLOG:-?}"

AFTER_NOTIF=$($MYSQL -e "SELECT COUNT(*) FROM notification;" 2>/dev/null | tr -d ' ')
AFTER_ROWS_INS=$(mysql_status Innodb_rows_inserted)
AFTER_ROWS_UPD=$(mysql_status Innodb_rows_updated)
ATTEMPTS=$($MYSQL -e "SELECT COUNT(*) FROM delivery_attempt;" 2>/dev/null | tr -d ' ')
SUCCEEDED=$($MYSQL -e "SELECT COUNT(*) FROM notification WHERE status='SUCCEEDED';" 2>/dev/null | tr -d ' ')
PENDING_LEFT=$($MYSQL -e "SELECT COUNT(*) FROM notification WHERE status IN ('PENDING','RETRY_WAIT');" 2>/dev/null | tr -d ' ')

NEW_NOTIF=$((AFTER_NOTIF - BEFORE_NOTIF))
NEW_INS=$((AFTER_ROWS_INS - BEFORE_ROWS_INS))
NEW_UPD=$((AFTER_ROWS_UPD - BEFORE_ROWS_UPD))
WRITE_PER_SEC=$(awk -v a="$NEW_INS" -v b="$NEW_UPD" -v s="$((DURATION + WARMUP))" 'BEGIN{printf "%.0f", (a+b)/s}')
INS_PER_SEC=$(awk -v a="$NEW_NOTIF" -v s="$((DURATION + WARMUP))" 'BEGIN{printf "%.0f", a/s}')

WORKERS_SIZE=$(metric notify_dispatch_workers_size)
WORKERS_SIZE=${WORKERS_SIZE:-200}
WORKER_AVG=$(awk '$2 != "" {n++; s+=$2} END {if (n==0) print "n/a"; else printf "%.1f", s/n}' "$LOG_SAMPLE")
UTIL=$(awk -v size="$WORKERS_SIZE" '$2 != "" {n++; s+=$2; if ($2 > m) m=$2} \
      END {if (n == 0) print "n/a"; else printf "%.1f%% 均值 / 峰值 %.0f 个线程", s/n*100/size, m}' "$LOG_SAMPLE")

say "3. 实测结果"
printf '%-26s %s\n' "受理 p99" "$(echo "$SUMMARY" | sed -n 's/.* p99_ms=\([^ ]*\).*/\1/p') ms（验收线 50ms）"
printf '%-26s %s\n' "受理 p50 / p999" "$(echo "$SUMMARY" | sed -n 's/.* p50_ms=\([^ ]*\).*/\1/p') / $(echo "$SUMMARY" | sed -n 's/.* p999_ms=\([^ ]*\).*/\1/p') ms"
printf '%-26s %s\n' "达成速率" "$(echo "$SUMMARY" | sed -n 's/.* qps=\([^ ]*\).*/\1/p') 请求/秒"
printf '%-26s %s\n' "状态码分布" "$(echo "$SUMMARY" | sed -n 's/.* status="\([^"]*\)".*/\1/p')"
printf '%-26s %s\n' "notification 插入" "${NEW_NOTIF} 行（含预热，≈${INS_PER_SEC} 行/秒）"
printf '%-26s %s\n' "delivery_attempt" "${ATTEMPTS} 行"
printf '%-26s %s\n' "InnoDB 写（插入+更新）" "${WRITE_PER_SEC} 行/秒（估算基线 350）"
printf '%-26s %s\n' "worker 平均在途" "${WORKER_AVG} / ${WORKERS_SIZE:-200}（${UTIL}）"
printf '%-26s %s\n' "终态" "SUCCEEDED ${SUCCEEDED} / 仍待投 ${PENDING_LEFT}"

say "4. 与设计文档 §7 的估算对比"
cat <<'EOF'
量级对照（结论见 docs/T4-交付.md）：
  · 受理 p99 目标 <50ms —— 纯受理（关调度器）约 17ms；投递同时满负荷时尾部抬到 40~60ms，
    抬升来自"投递的 2 次写 + 认领的 1 次写"与受理 INSERT 争同一份 redo log（p50 全程不动，证明不是代码路径慢）
  · DB 写入 §7 估算 ~350 行/秒 —— 那是按"每单 2 写"算的；实测 600 行/秒 = 150 QPS × 4 写
    （notification 插入 + 认领 UPDATE + 明细 INSERT + 终态 UPDATE），§7 应修正为每单 4 写
  · worker 利用率低是设计意图：200 是按"下游整体慢到 1s"取的；下游 200ms 时实测均值 26（≈150×0.2），
    与 Little's Law 一致——真到 1s 也就 150/200，留 25% 余量给抖动
EOF
echo
echo "日志：$LOG_APP / $LOG_LOAD / $LOG_SAMPLE"
