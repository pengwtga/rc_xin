#!/usr/bin/env bash
# T2.4 出站安全与凭证加密的现场演示：
#   1) 接入期拒绝元数据地址与内网地址（422）
#   2) 凭证在库里是密文（HEX 里搜不到明文）
#   3) 投递期拦截"绕过接入直接写入"的内网目标（一次即死信，请求一个字节都没发出）
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18083
MOCK_PORT=9103
LOG_APP=/tmp/t24_app.log
LOG_MOCK=/tmp/t24_mock.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 notify"

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null
}
trap cleanup EXIT

say() { echo; echo "== $*"; }

python3 scripts/mock_downstream.py --port "$MOCK_PORT" >"$LOG_MOCK" 2>&1 &
MOCK_PID=$!

# 白名单只开 127.0.0.1（演示要打本机 mock）；其余私网一律禁止
NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java -jar target/rc-xin.jar --server.port="$APP_PORT" >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "http://127.0.0.1:${APP_PORT}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
echo "应用已就绪（:${APP_PORT}），白名单=[127.0.0.1]"

$MYSQL -e "DELETE FROM delivery_attempt; DELETE FROM notification;" 2>/dev/null

submit() {
  curl -s --noproxy '*' -w '\nHTTP %{http_code}\n' -X POST "http://127.0.0.1:${APP_PORT}/v1/notifications" \
    -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d "$1"
}

say "1. 提交 AWS/腾讯云 metadata 地址"
submit '{"idempotency_key":"t24_meta","target_url":"http://169.254.169.254/latest/meta-data/","http_method":"GET"}'

say "2. 提交阿里云 metadata 地址（CGNAT 段，最容易被漏）"
submit '{"idempotency_key":"t24_ali","target_url":"http://100.100.100.200/latest/meta-data/","http_method":"GET"}'

say "3. 提交内网地址与明文 http 的公网地址"
submit '{"idempotency_key":"t24_lan","target_url":"http://10.0.0.5/cb"}'
submit '{"idempotency_key":"t24_http","target_url":"http://crm.example.com/cb"}'

say "4. 正常目标（白名单内）：受理成功，凭证加密入库"
submit '{"idempotency_key":"t24_ok","target_url":"http://127.0.0.1:9103/cb",
 "headers":{"Authorization":"Bearer sk_live_SECRET"},"body":"{\"phone\":\"13800138000\"}"}'
sleep 2

$MYSQL -e "SELECT id, status, LENGTH(headers_enc) AS headers_bytes, HEX(headers_enc) AS headers_hex
FROM notification WHERE idempotency_key='t24_ok'\G" 2>/dev/null
# 注意：$(($MYSQL ...)) 会被 bash 当成算术展开，必须先赋值再拼接
leaked=$($MYSQL -N -e "SELECT LOCATE('sk_live_SECRET', headers_enc) FROM notification WHERE idempotency_key='t24_ok';" 2>/dev/null)
echo "— 在库里的字节中搜明文凭证 sk_live_SECRET：位置 ${leaked}（0 = 搜不到）"

say "5. 投递期拦截：直接写库绕过接入校验（等价于 DNS rebinding 或历史脏数据）"
$MYSQL -e "INSERT INTO notification
  (caller_id, idempotency_key, target_url, http_method, headers_enc, body_enc, status, attempt_count, max_attempts, next_attempt_at)
  VALUES ('shop','t24_rebind','http://10.0.0.5/cb','POST',NULL,NULL,'PENDING',0,10,NOW(3));" 2>/dev/null
sleep 3

$MYSQL -e "SELECT n.status, n.attempt_count, n.last_error_class, a.duration_ms
FROM notification n JOIN delivery_attempt a ON a.notification_id = n.id
WHERE n.idempotency_key='t24_rebind'\G" 2>/dev/null
echo "— 投递器日志（未发出请求的那条）："
grep "安全策略拒绝" "$LOG_APP" | sed 's/^.*安全策略/安全策略/'
