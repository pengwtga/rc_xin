#!/usr/bin/env bash
# T2.3 死信终态的现场演示：一条 max_attempts=1 的通知打到一个恒 502 的下游，
# 观察 尝试 1 次 → DEAD → 死信日志（含 reason / error_class / next_action）。
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18082
MOCK_PORT=9102
LOG_APP=/tmp/t23_app.log
LOG_MOCK=/tmp/t23_mock.log

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

python3 scripts/mock_downstream.py --port "$MOCK_PORT" --status 502 >"$LOG_MOCK" 2>&1 &
MOCK_PID=$!

java -jar target/rc-xin.jar --server.port="$APP_PORT" >"$LOG_APP" 2>&1 &
APP_PID=$!

for _ in $(seq 1 60); do
  curl -s --noproxy '*' "http://127.0.0.1:$APP_PORT/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
echo "== 应用已就绪（:${APP_PORT}），下游 mock 恒返回 502（:${MOCK_PORT}）"

docker exec rc-xin-mysql mysql -unotify -pnotify123 notify -e "
DELETE FROM delivery_attempt WHERE notification_id IN (SELECT id FROM notification WHERE idempotency_key='t23_demo');
DELETE FROM notification WHERE idempotency_key='t23_demo';
INSERT INTO notification
  (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
   status, attempt_count, max_attempts, next_attempt_at)
VALUES ('shop','t23_demo','http://127.0.0.1:$MOCK_PORT/cb','POST',NULL,NULL,'PENDING',0,1,NOW(3));" 2>/dev/null
echo "== 已提交：max_attempts=1，下游 502"

sleep 4

echo "== notification 终态"
docker exec rc-xin-mysql mysql -unotify -pnotify123 notify -e "
SELECT id, status, attempt_count, max_attempts, last_status_code, last_error_class
FROM notification WHERE idempotency_key='t23_demo'\G" 2>/dev/null

echo "== delivery_attempt 明细"
docker exec rc-xin-mysql mysql -unotify -pnotify123 notify -e "
SELECT attempt_no, status_code, error_class, duration_ms
FROM delivery_attempt WHERE notification_id=(SELECT id FROM notification WHERE idempotency_key='t23_demo');" 2>/dev/null

echo "== 死信日志（logger=notify.deadletter）"
grep "dead_letter" "$LOG_APP" | sed 's/^.*dead_letter/dead_letter/'
