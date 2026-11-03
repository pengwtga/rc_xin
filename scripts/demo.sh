#!/usr/bin/env bash
# 5 分钟 Demo：一条通知从"提交"到"成功/失败/死信/重放"的完整一生。
#
# 前置（README 前三条命令）：
#   docker compose up -d
#   mvn -DskipTests package
#   bash scripts/demo.sh
#
# 全程自己起 mock 与实例、自己清库，不需要手工改任何配置；生产默认参数之外只压了三处
# 与"演示等得起"有关的（退避基数 1s、尝试预算 2 次、采样 2s），都在下面的启动参数里写明了。
set -u
cd "$(dirname "$0")/.." || exit 1

APP_PORT=18080
MOCK_OK=9201
MOCK_FAIL=9202
MOCK_4XX=9203
LOG_APP=/tmp/demo_app.log
MYSQL="docker exec rc-xin-mysql mysql -unotify -pnotify123 -N -B notify"
BASE="http://127.0.0.1:${APP_PORT}"

[ -f target/rc-xin.jar ] || { echo "先打包: mvn -DskipTests package"; exit 1; }

cleanup() {
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  for pid in ${MOCK_PIDS:-}; do kill "$pid" 2>/dev/null; done
}
trap cleanup EXIT

say() { echo; echo "──────── $* ────────"; }
q() { $MYSQL -e "$1" 2>/dev/null; }

submit() {  # $1=幂等键 $2=目标 $3=body 纯文本 $4=headers（JSON 对象，可省）
  python3 - "$1" "$2" "$3" "${4:--}" >/tmp/demo_body.json <<'PY'
import json, sys
key, target, body, hdr = sys.argv[1:5]
d = {"idempotency_key": key, "target_url": target, "body": body}
if hdr != "-":
    d["headers"] = json.loads(hdr)
print(json.dumps(d))
PY
  curl -s --noproxy '*' -X POST "${BASE}/v1/notifications" \
    -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d @/tmp/demo_body.json
}

submit_id() {  # 提交并只取单号；取不到就让脚本停下，避免后面拿空单号继续跑出假结论
  local resp id
  resp=$(submit "$@")
  id=$(echo "$resp" | sed -n 's/.*"notification_no":\([0-9]*\).*/\1/p')
  if [ -z "$id" ]; then
    echo "提交失败：$resp" >&2
    exit 1
  fi
  echo "$id"
}

say "0. 起三个下游：正常 200 / 恒定 500 / 恒定 400，再起一个实例"
q "DELETE FROM delivery_attempt; DELETE FROM notification;"
MOCK_PIDS=""
python3 scripts/mock_downstream.py --port "$MOCK_OK" --status 200 >/tmp/demo_mock_ok.log 2>&1 & MOCK_PIDS="$MOCK_PIDS $!"
python3 scripts/mock_downstream.py --port "$MOCK_FAIL" --status 500 >/tmp/demo_mock_fail.log 2>&1 &
MOCK_FAIL_PID=$!
MOCK_PIDS="$MOCK_PIDS $MOCK_FAIL_PID"
python3 scripts/mock_downstream.py --port "$MOCK_4XX" --status 400 >/tmp/demo_mock_400.log 2>&1 & MOCK_PIDS="$MOCK_PIDS $!"

# 演示参数：退避基数 1s（默认 10s，等不起）、尝试预算 2 次（默认 10，同样等不起）、
# 采样 2s（默认 10s，为了让指标尽早可用）。这不改变任何语义，只压缩时间轴。
NOTIFY_SSRF_ALLOWED_HOSTS=127.0.0.1 java -jar target/rc-xin.jar --server.port="$APP_PORT" \
  --notify.backoff.base-ms=1000 --notify.retry.max-attempts=2 \
  --notify.metrics.sample-interval-ms=2000 >"$LOG_APP" 2>&1 &
APP_PID=$!
for _ in $(seq 1 60); do
  curl -s --noproxy '*' "${BASE}/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  sleep 1
done
curl -s --noproxy '*' "${BASE}/actuator/health" >/dev/null || { echo "实例没起来，看 $LOG_APP"; exit 1; }
echo "实例就绪：${BASE}"

say "1. 幂等：同一个 idempotency_key 提交两次，第二次返回同一个单号"
R1=$(submit "demo:idem" "http://127.0.0.1:${MOCK_OK}/cb" '{"phone":"13800001111"}')
R2=$(submit "demo:idem" "http://127.0.0.1:${MOCK_OK}/cb" '{"phone":"13800001111"}')
echo "第一次: $R1"
echo "第二次: $R2"
echo "→ 两次单号相同，表里只有一行：$(q "SELECT COUNT(*) FROM notification WHERE idempotency_key='demo:idem'")"

say "2. 正常投递：2 秒后变 SUCCEEDED，查单能看到逐次明细"
N1=$(echo "$R1" | sed -n 's/.*"notification_no":\([0-9]*\).*/\1/p')
sleep 3
echo "库里：$(q "SELECT status, attempt_count FROM notification WHERE id=$N1")"
curl -s --noproxy '*' "${BASE}/v1/notifications/${N1}" -H 'X-Api-Key: k_shop' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("状态:",d["status"],"| 明细:",[(a["attempt_no"],a["status_code"],a["error_class"]) for a in d["attempts"]])'

say "3. 查询侧的保护：凭证被脱敏、body 不回显"
N2=$(submit_id "demo:secret" "http://127.0.0.1:${MOCK_OK}/cb" '{"phone":"13800001111"}' \
      '{"Authorization":"Bearer real-supplier-token","Content-Type":"application/json"}')
sleep 2
curl -s --noproxy '*' "${BASE}/v1/notifications/${N2}" -H 'X-Api-Key: k_shop' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("headers:",[(h["name"],h["value"]) for h in d["headers"]]); print("has_body:",d["has_body"],"（不回显内容）")'
echo "→ 响应里出现 real-supplier-token 的次数：$(curl -s --noproxy '*' "${BASE}/v1/notifications/${N2}" -H 'X-Api-Key: k_shop' | grep -c real-supplier-token)"
echo "→ 别人（k_crm）查这一单：$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "${BASE}/v1/notifications/${N2}" -H 'X-Api-Key: k_crm')（与「不存在」同一个响应）"

say "4. 失败分级：500 按退避重试到预算耗尽进 DEAD；400 一次就进 DEAD"
N3=$(submit_id "demo:500" "http://127.0.0.1:${MOCK_FAIL}/cb" '{"x":1}')
N4=$(submit_id "demo:400" "http://127.0.0.1:${MOCK_4XX}/cb" '{"x":2}')
sleep 8
echo "500 那条：$(q "SELECT status, attempt_count, max_attempts, last_status_code, last_error_class FROM notification WHERE id=$N3")"
echo "  明细：$(q "SELECT GROUP_CONCAT(CONCAT(attempt_no,':',status_code,':',error_class) ORDER BY attempt_no) FROM delivery_attempt WHERE notification_id=$N3")"
echo "400 那条：$(q "SELECT status, attempt_count, last_status_code, last_error_class FROM notification WHERE id=$N4")"
echo "  → 4xx 是 PERMANENT：一次即 DEAD，不浪费预算（对比 500 那条试了 2 次）"

say "5. 死信是拉取式的：按状态查列表，不需要谁主动推给我"
curl -s --noproxy '*' "${BASE}/v1/notifications?status=DEAD&limit=5" -H 'X-Api-Key: k_shop' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("死信：",[(i["notification_no"],i["last_error_class"]) for i in d["items"]],"next_cursor:",d["next_cursor"])'

say "6. 人工重放：供应商恢复后，把 DEAD 放回队列（不是同步投递）"
kill "$MOCK_FAIL_PID" 2>/dev/null
wait "$MOCK_FAIL_PID" 2>/dev/null
python3 scripts/mock_downstream.py --port "$MOCK_FAIL" --status 200 >/tmp/demo_mock_fail.log 2>&1 &
MOCK_PIDS="$MOCK_PIDS $!"
sleep 1
echo "重放：$(curl -s --noproxy '*' -X POST "${BASE}/v1/notifications/${N3}/replay" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  -d '{"operator":"zhang.san","reason":"供应商已恢复"}')"
echo "再点一次（预期 409）：$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' -X POST "${BASE}/v1/notifications/${N3}/replay" \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d '{"reason":"重复点击"}')"
sleep 3
echo "重放后：$(q "SELECT status, attempt_count, max_attempts FROM notification WHERE id=$N3")，明细序号连续：$(q "SELECT GROUP_CONCAT(attempt_no ORDER BY attempt_no) FROM delivery_attempt WHERE notification_id=$N3")"

say "7. 指标：Prometheus 抓得到，且抓取不查库"
curl -s --noproxy '*' "${BASE}/actuator/prometheus" | grep -E "^notify_" \
  | sed -e 's/{application="rc-notify",/{/' -e 's/{} //' | sort

say "8. 收尾"
echo "日志：${LOG_APP}（含一条 dead_letter WARN——死信的「单一宣布者」）"
grep -m1 dead_letter "$LOG_APP" | sed 's/.*dead_letter/dead_letter/'
