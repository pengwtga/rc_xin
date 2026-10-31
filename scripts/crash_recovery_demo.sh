#!/usr/bin/env bash
#
# T2.2 崩溃恢复演示：投递中途 kill -9，另一个实例在租约到期后接手并完成。
#
# 演示的是一条完整的 at-least-once 链路：
#   1. 实例 A 认领任务 → 下游已收到请求（mock sleep 8s，A 卡在等响应）
#   2. kill -9 A：结果永远不会落库，行停在 PROCESSING，租约 60s 后到期
#   3. 起实例 B：同一张表、不同 instanceId，租约到期后把行认领走
#   4. 下游重启为快响应 → B 投递成功 → SUCCEEDED，attempt_count=2
#   5. delivery_attempt 里只有 B 写的那一行（attempt_no=2）——崩溃的那次没有明细，
#      这就是"请求已发出、结果未落库"窗口的可观测证据；mock 日志里能看到下游收到 2 次
#
# 用法：bash scripts/crash_recovery_demo.sh
# 前置：docker compose up -d（MySQL 在 33061）；首次运行会自动 mvn package
#
set -uo pipefail
cd "$(dirname "$0")/.."

APP_A=18080
APP_B=18081
MOCK=9101
JAR=target/rc-xin.jar
CT=rc-xin-mysql
DB=notify
DBU=notify
DBP=notify123
KEY="crash_demo_$(date +%s)"
LOG_MOCK=/tmp/crash_demo_mock.log
LOG_A=/tmp/crash_demo_a.log
LOG_B=/tmp/crash_demo_b.log

PID_A=""
PID_B=""
PID_MOCK=""

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
db()  { docker exec "$CT" mysql -u"$DBU" -p"$DBP" -N -B "$DB" -e "$1" 2>/dev/null; }

cleanup() {
  [[ -n "$PID_B"   ]] && kill -9 "$PID_B"   2>/dev/null
  [[ -n "$PID_A"   ]] && kill -9 "$PID_A"   2>/dev/null
  [[ -n "$PID_MOCK" ]] && kill "$PID_MOCK"  2>/dev/null
}
trap cleanup EXIT

wait_health() {  # $1=port $2=seconds
  for _ in $(seq 1 "$2"); do
    curl -sf --noproxy '*' "http://127.0.0.1:$1/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "实例 :$1 未在 $2s 内就绪，日志：$LOG_A / $LOG_B" >&2
  return 1
}

wait_status() {  # $1=id $2=目标状态 $3=seconds
  for _ in $(seq 1 "$3"); do
    [[ "$(db "SELECT status FROM notification WHERE id=$1")" == "$2" ]] && return 0
    sleep 1
  done
  return 1
}

# ---------- 0. 前置检查 ----------
say "0. 前置检查"
if ! docker ps --format '{{.Names}}' | grep -q "^$CT$"; then
  echo "MySQL 容器 $CT 未运行，先执行：docker compose up -d" >&2
  exit 1
fi
if [[ ! -f "$JAR" ]]; then
  echo "构建可执行 jar …"
  mvn -q -o package -DskipTests || mvn -q package -DskipTests || exit 1
fi
echo "jar=$JAR  key=$KEY"

# 清掉上一轮演示残留，避免干扰观察
db "DELETE FROM delivery_attempt WHERE notification_id IN (SELECT id FROM notification WHERE idempotency_key LIKE 'crash_demo_%');
    DELETE FROM notification WHERE idempotency_key LIKE 'crash_demo_%';"

# ---------- 1. 慢下游（8s 才响应，让 A 卡在"已发出、未返回"） ----------
say "1. 启动慢下游 mock :${MOCK}（sleep 8s）"
python3 scripts/mock_downstream.py --port "$MOCK" --sleep 8 >"$LOG_MOCK" 2>&1 &
PID_MOCK=$!
sleep 1

# ---------- 2. 实例 A ----------
say "2. 启动实例 A :$APP_A"
java -jar "$JAR" --server.port=$APP_A >"$LOG_A" 2>&1 &
PID_A=$!
wait_health $APP_A 60 || exit 1

# ---------- 3. 提交任务，确认 A 认领且下游已收到 ----------
say "3. 提交通知并等待 A 认领"
curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_A/v1/notifications" \
  -H 'X-Api-Key: k_shop' -H 'Content-Type: application/json' \
  -d "{\"idempotency_key\":\"$KEY\",\"target_url\":\"http://127.0.0.1:$MOCK/cb\",\"body\":\"{\\\"order\\\":1}\"}"
echo
ID=$(db "SELECT id FROM notification WHERE idempotency_key='$KEY'")
[[ -z "$ID" ]] && { echo "落库失败，看 $LOG_A" >&2; exit 1; }
wait_status "$ID" PROCESSING 10 || { echo "A 未认领，看 $LOG_A" >&2; exit 1; }
sleep 1
echo "任务 id=$ID 状态=PROCESSING"
grep -q "mock #1" "$LOG_MOCK" && echo "下游已收到第 1 次请求（A 正在等响应，结果尚未落库）"

# ---------- 4. kill -9 ----------
say "4. kill -9 实例 A（模拟进程崩溃）"
kill -9 "$PID_A"; wait "$PID_A" 2>/dev/null; PID_A=""
db "SELECT CONCAT('  row: status=', status, ' attempt_count=', attempt_count,
        ' lease_owner=', IFNULL(lease_owner,'NULL'),
        ' 租约剩余秒=', TIMESTAMPDIFF(SECOND, NOW(3), lease_until))
    FROM notification WHERE id=$ID"

# ---------- 5. 下游恢复成快响应 ----------
say "5. 下游恢复（mock 重启为立即 200）"
HITS_FIRST=$(grep -c 'mock #' "$LOG_MOCK")
kill "$PID_MOCK" 2>/dev/null; wait "$PID_MOCK" 2>/dev/null; PID_MOCK=""
python3 scripts/mock_downstream.py --port "$MOCK" >>"$LOG_MOCK" 2>&1 &
PID_MOCK=$!
sleep 1

# ---------- 6. 实例 B ----------
say "6. 启动实例 B :${APP_B}（同一张表，不同 instanceId）"
java -jar "$JAR" --server.port=$APP_B >"$LOG_B" 2>&1 &
PID_B=$!
wait_health $APP_B 60 || exit 1
echo "B 已就绪，等待租约到期（最多 60s，这是崩溃恢复的固有延迟）…"

# ---------- 7. 等接手完成 ----------
if wait_status "$ID" SUCCEEDED 90; then
  say "7. 恢复完成"
else
  say "7. 90s 内未完成，最后状态：$(db "SELECT status FROM notification WHERE id=$ID")"
  tail -20 "$LOG_B"
  exit 1
fi

# ---------- 8. 结果核对 ----------
say "8. 结果核对"
echo "— notification："
db "SELECT CONCAT('  status=', status, ' attempt_count=', attempt_count,
        ' max_attempts=', max_attempts, ' last_status_code=', IFNULL(last_status_code,'NULL'),
        ' lease_owner=', IFNULL(lease_owner,'NULL'))
    FROM notification WHERE id=$ID"
echo "— delivery_attempt（只应有接手者写的一行，attempt_no=2）："
db "SELECT CONCAT('  attempt_no=', attempt_no, ' status_code=', IFNULL(status_code,'NULL'),
        ' duration_ms=', IFNULL(duration_ms,'NULL'))
    FROM delivery_attempt WHERE notification_id=$ID ORDER BY attempt_no"
echo "— 下游收到请求次数：$(grep -c 'mock #' "$LOG_MOCK")"
echo "  其中崩溃前 $HITS_FIRST 次由 A 发出（响应随进程一起消失），接手后由 B 重发——这就是 at-least-once 的语义代价"
echo "— 守卫信号（B 的日志里不应出现「被守卫丢弃」）："
grep -c "被守卫丢弃" "$LOG_B" | sed 's/^/  staleResults=/'
echo
echo "结论：崩溃没有产生任何恢复代码，只靠认领条件里的 lease_until < NOW(3) 完成接手。"
