#!/usr/bin/env bash
# T3.2 人工重放的现场演示：
#   1) 一条已进 DEAD 的通知（两次 502 的历史）指到"已恢复"的下游
#   2) POST /replay → 200：回 PENDING，attempt_count 不归零，预算追加一档
#   3) 调度器自然投递 → SUCCEEDED，attempt_no 连续（1、2 失败，3 成功）
#   4) 再点一次 replay → 409（状态守卫，无需分布式锁）
#   5) 查单详情的 replays 字段：谁、为什么、给了多少预算
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18085
MOCK_PORT=9105
LOG_APP=/tmp/t32_app.log
LOG_MOCK=/tmp/t32_mock.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 notify"
BASE="http://127.0.0.1:${APP_PORT}/v1/notifications"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

# 这次的 mock 是健康的（200）——模拟"供应商已恢复"
python3 scripts/mock_downstream.py --port "$MOCK_PORT" >"$LOG_MOCK" 2>&1 &
MOCK_PID=$!

java -jar target/rc-xin.jar --server.port="$APP_PORT" \
  --notify.ssrf.allowed-hosts=127.0.0.1 >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
if ! curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
  echo "应用未就绪，日志尾部："; tail -20 "$LOG_APP"; exit 1
fi
echo "应用已就绪（:${APP_PORT}），下游 mock 恒返回 200（:${MOCK_PORT}，模拟供应商已恢复）"

$MYSQL --default-character-set=utf8mb4 -e "DELETE FROM replay_log; DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null

say "1. 造一条 DEAD 单：两次 502 的历史，预算 2/2 已花完（跳过重放 SQL 注入的争议，历史明细一并造好）"
$MYSQL --default-character-set=utf8mb4 -e "
INSERT INTO notification
  (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc,
   status, attempt_count, max_attempts, next_attempt_at, last_status_code, last_error_class)
VALUES ('shop','t32_demo','http://127.0.0.1:${MOCK_PORT}/cb','POST',NULL,NULL,'DEAD',2,2,NOW(3),502,'HTTP_5XX');
SET @id = LAST_INSERT_ID();
INSERT INTO delivery_attempt (notification_id, attempt_no, started_at, duration_ms, status_code, error_class, response_snippet)
VALUES (@id,1,NOW(3) - INTERVAL 60 SECOND,905,502,'HTTP_5XX','{\"ok\":true}'),
       (@id,2,NOW(3) - INTERVAL 30 SECOND,812,502,'HTTP_5XX','{\"ok\":true}');
SELECT id, status, attempt_count, max_attempts, last_status_code FROM notification;" 2>/dev/null
NO=$($MYSQL -N -B -e "SELECT id FROM notification WHERE idempotency_key='t32_demo';" 2>/dev/null)
echo "→ 单号 ${NO}（DEAD，下游此刻已恢复）"

say "2. 人工重放：带操作人与原因（reason 必填——强制留下'为什么重放'）"
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' -X POST "$BASE/${NO}/replay" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  --data-raw '{"operator":"zhang.san","reason":"供应商已恢复，重新投递"}'

say "3. 等 3 秒，让调度器自然投递（预算 12，退避从第 3 次尝试继续）"
sleep 3
$MYSQL -e "SELECT status, attempt_count, max_attempts, last_status_code FROM notification WHERE id=${NO};" 2>/dev/null
echo "-- delivery_attempt（attempt_no 连续：1、2 是重放前的失败，3 是重放后的成功）"
$MYSQL -e "SELECT attempt_no, status_code, error_class FROM delivery_attempt WHERE notification_id=${NO} ORDER BY attempt_no;" 2>/dev/null

say "4. 再点一次 replay → 409（状态守卫写在 UPDATE 的 WHERE 里，不需要分布式锁）"
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' -X POST "$BASE/${NO}/replay" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  --data-raw '{"operator":"zhang.san","reason":"手滑多点了一次"}'

say "5. 对 SUCCEEDED 的单重放 → 同样 409 且无副作用"
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' -X POST "$BASE/${NO}/replay" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  --data-raw '{"operator":"zhang.san","reason":"再试一次"}'

say "6. 查单详情：replays 补上 DEAD→PENDING 的时间线空洞"
curl -s --noproxy '*' "$BASE/${NO}" -H 'X-Api-Key: k_shop' | python3 -c "
import json,sys
d = json.load(sys.stdin)
print('status           :', d['status'], '| attempt_count:', d['attempt_count'], '/', d['max_attempts'])
print('last_error_class :', d['last_error_class'], '(成功后被 markSucceeded 清空；重放时刻仍保留旧值)')
print('replays          :')
for r in d['replays']:
    print('  -', r['operator'], '|', r['reason'], '| attempt_before:', r['attempt_count_before'],
          '| budget:', r['max_attempts_after'], '|', r['replayed_at'])
print('attempts         :', [(a['attempt_no'], a['status_code']) for a in d['attempts']])"

say "完成。要点：重放只回队列、不重置历史；谁在什么时候给了多少预算，审计表与查单详情都可见。"
