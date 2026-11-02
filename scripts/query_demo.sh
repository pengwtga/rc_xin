#!/usr/bin/env bash
# T3.1 查询接口的现场演示：
#   1) 一条通知打到恒 502 的下游，两次尝试后进 DEAD
#   2) GET 单号 → 状态 + 每轮 attempt（状态码/耗时/错误分类/响应片段）
#   3) Header 脱敏（Authorization 变 ****，Content-Type 原样）、body 只回答有无
#   4) 列表按状态过滤 + 键集分页
#   5) 他人单号 → 404（与"不存在"同一个响应）；非法参数 → 422 / 400
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18084
MOCK_PORT=9104
LOG_APP=/tmp/t31_app.log
LOG_MOCK=/tmp/t31_mock.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 notify"
BASE="http://127.0.0.1:${APP_PORT}/v1/notifications"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

python3 scripts/mock_downstream.py --port "$MOCK_PORT" --status 502 >"$LOG_MOCK" 2>&1 &
MOCK_PID=$!

# 退避基数调到 1s，让"第二次尝试"在演示里等得起（生产是 10s 起）
NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java -jar target/rc-xin.jar \
  --server.port="$APP_PORT" --notify.backoff.base-ms=1000 >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
if ! curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
  echo "应用未就绪，日志尾部："
  tail -20 "$LOG_APP"
  exit 1
fi
echo "应用已就绪（:${APP_PORT}），下游 mock 恒返回 502（:${MOCK_PORT}）"

$MYSQL -e "DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null

say "1. 提交一条通知（带 Authorization 与 body）"
RESP=$(curl -s --noproxy '*' -X POST "$BASE" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d "{
    \"idempotency_key\": \"order_paid:t31_demo\",
    \"target_url\": \"http://127.0.0.1:${MOCK_PORT}/cb\",
    \"http_method\": \"POST\",
    \"headers\": {\"Authorization\": \"Bearer real-secret-token\", \"Content-Type\": \"application/json\"},
    \"body\": \"{\\\"phone\\\":\\\"13800001111\\\"}\"
  }")
echo "$RESP"
NO=$(echo "$RESP" | sed -n 's/.*"notification_no":\([0-9]*\).*/\1/p')
# 演示用：把预算压到 2，两次 502 就进 DEAD（生产默认 10 次、退避累计约 3.5 小时）
$MYSQL -e "UPDATE notification SET max_attempts = 2 WHERE id = ${NO};" 2>/dev/null
echo "→ 单号 ${NO}，预算已压到 2（演示用）"

say "2. 等两次尝试结束（502 + 退避 1s，约 6s）"
sleep 6
$MYSQL -e "SELECT id, status, attempt_count, max_attempts, last_status_code, last_error_class
           FROM notification WHERE id = ${NO}\G" 2>/dev/null

say "3. 查单：状态 + 每轮明细（关键看 headers 与 body）"
curl -s --noproxy '*' "$BASE/${NO}" -H 'X-Api-Key: k_shop' | python3 -m json.tool

say "4. 库里其实存着什么（密文，且 body 明文在库）"
$MYSQL -e "SELECT id,
                  LENGTH(headers_enc) AS headers_enc_bytes,
                  LENGTH(body_enc)    AS body_enc_bytes,
                  status
           FROM notification WHERE id = ${NO};" 2>/dev/null

say "5. 列表：按状态过滤 + 键集分页"
curl -s --noproxy '*' "$BASE?status=DEAD&limit=2" -H 'X-Api-Key: k_shop' | python3 -m json.tool

say "6. 他人单号：404（与不存在的单号同一个响应，不泄露存在性）"
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' "$BASE/${NO}" -H 'X-Api-Key: k_crm'
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' "$BASE/999999999" -H 'X-Api-Key: k_crm'

say "7. 参数非法：status=FOO → 422；单号写成 abc → 400（不是 500）"
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' "$BASE?status=FOO" -H 'X-Api-Key: k_shop'
curl -s --noproxy '*' -w ' [HTTP %{http_code}]\n' "$BASE/abc" -H 'X-Api-Key: k_shop'

say "完成。要点：body 与凭证明文都不出现在任何查询响应里，但每次尝试的状态码/耗时/错误分类都能查到。"
