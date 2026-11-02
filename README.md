# rc_xin — API 通知系统

可靠出站 HTTP 投递服务：请求先落盘（不丢）、投递有租约（不卡死）、失败有分级（不白重试）。

> 设计文档与任务拆解见仓库外层工作目录（`API通知系统_设计文档_V2.md` / `API通知系统_任务拆解清单.md` / `API通知系统_参数定值依据.md`）。

## 快速启动（M0）

```bash
# 1. 起 MySQL 8.0.46（localhost:33061）
docker compose up -d

# 2. 打包
mvn -q -DskipTests package

# 3. 启动（Flyway 自动建表）
java -jar target/rc-xin.jar

# 4. 健康检查
curl -s http://localhost:8080/actuator/health   # -> {"status":"UP"}
```

## 里程碑进度

- [x] **M0** 骨架可启动、可迁移、可测试（T0.1 结构 / T0.2 建表 / T0.3 启动自检）
- [x] **M1** 能存能投（T1.1 接入接口 / T1.2 投递引擎 / T1.3 幂等去重）
- [x] **M2** 可靠投递（T2.1 失败分级 / T2.2 租约恢复 / T2.3 死信终态 / T2.4 出站安全 / T2.5 端点隔离与熔断）
- [x] **M3** 可观测与运维（查询 / 重放 / 指标 / 背压 / 归档）
  - [x] T3.1 查询接口：`GET /v1/notifications/{id}`（Header 脱敏 + 逐次明细）、`GET /v1/notifications?status=&cursor=`（键集分页）
  - [x] T3.2 人工重放：`POST /v1/notifications/{id}/replay`（仅 DEAD、attempt_count 不归零、预算追加一档、审计同事务落库）
  - [x] T3.3 指标 / 背压 / 归档：`/actuator/prometheus` 12 个指标、积压超阈值 `503 + Retry-After`、成功 24h / 失败 7d 清密文（不删行）
- [ ] M4 交付（压测 / README / AI 会话记录）

## 故障演示

```bash
# 投递中途 kill -9 一个实例，另一个实例在租约到期后接手（约 70s，含 60s 租约等待）
bash scripts/crash_recovery_demo.sh

# 一条通知打到恒 502 的下游，观察 max_attempts=1 直接进 DEAD 与死信日志（约 15s）
bash scripts/dead_letter_demo.sh

# 出站安全：metadata/内网地址被拒 422、凭证密文入库、投递期拦下绕过接入的内网目标（约 10s）
bash scripts/ssrf_demo.sh

# 端点隔离：一个供应商恒 500，熔断后任务只推迟不烧预算；另一个供应商全部成功（约 25s）
bash scripts/circuit_breaker_demo.sh

# 查询接口：查单看逐次明细（Header 脱敏、body 不回显）、列表拉死信、越权 404（约 15s）
bash scripts/query_demo.sh

# 人工重放：DEAD 单回队列、attempt_count 不归零、预算追加一档、重复点击 409（约 15s）
bash scripts/replay_demo.sh

# 指标 / 背压 / 归档：抓 5 个核心指标、积压超阈值 503 + Retry-After、清密文但保留行（约 25s）
bash scripts/observability_demo.sh

# 慢/坏下游，供手工验收
python3 scripts/mock_downstream.py --port 9001 --sleep 20   # 触发 15s 超时
python3 scripts/mock_downstream.py --port 9001 --status 500 # 触发可重试失败
```

原理与取舍见 `docs/T2.2-租约与崩溃恢复.md`、`docs/T2.3-死信终态与尝试预算.md`、`docs/T2.4-出站安全与凭证加密.md`、`docs/T2.5-端点隔离与熔断.md`、`docs/T3.1-查询接口与逐次明细.md`、`docs/T3.2-人工重放.md`、`docs/T3.3-指标背压与归档.md`。

## 查询接口（T3.1）

```bash
# 单号详情：状态 + 每轮 attempt（状态码/耗时/错误分类/响应片段）；Header 只回显白名单值，body 只回答有无
curl -s 'http://localhost:8080/v1/notifications/1' -H 'X-Api-Key: k_shop' | python3 -m json.tool

# 死信清单：键集分页，next_cursor 为 null 即到底
curl -s 'http://localhost:8080/v1/notifications?status=DEAD&limit=20' -H 'X-Api-Key: k_shop'

# 人工重放（reason 必填）：仅 DEAD 可重放，attempt_count 不归零、预算追加一档
curl -s -X POST 'http://localhost:8080/v1/notifications/1/replay' \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  -d '{"operator":"zhang.san","reason":"供应商已恢复，重新投递"}'
```

## 监控

```bash
# 12 个 notify_* 指标：受理 QPS/错误率、幂等命中、队列深度、队头逾期秒数、投递结果（按端点）、
# 跳过、过期结果、死信、熔断端点数、归档清理量
curl -s http://localhost:8080/actuator/prometheus | grep '^notify_'

# 队列不健康时新提交返回 503 + Retry-After，且不落库（只挡提交，查询与重放照常）
curl -s -i -X POST http://localhost:8080/v1/notifications -H 'X-Api-Key: k_shop' -d '{}'
```

成功率由原始计数算出，不额外存 Gauge：

```promql
sum(rate(notify_delivery_attempts_total{result="success"}[5m]))
  / sum(rate(notify_delivery_attempts_total[5m]))
```

## 配置

全部参数收口在 `application.yml` 的 `notify.*`，启动自检（`StartupGuard`）保证：
租约 > HTTP 总超时 + 余量、批大小 ≤ worker 池、单端点并发 ≤ worker 池等约束被破坏时**拒绝启动**。
