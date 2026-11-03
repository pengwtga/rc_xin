# rc_xin — API 通知系统

可靠出站 HTTP 投递服务：**请求先落盘（不丢）、投递有租约（不卡死）、失败有分级（不白重试）**。
单机 MySQL 即队列，不依赖 MQ / Redis。

> 设计文档与任务拆解在仓库外层工作目录：`API通知系统_设计文档_V2.md`、`API通知系统_任务拆解清单.md`、`API通知系统_参数定值依据.md`。

## 三个问题

| | 答案 |
| --- | --- |
| **边界在哪** | 设计点 **150 QPS**（容量校验 200 QPS，日量 100 万条）。单实例 MySQL 撑到"日量千万"就翻转——那时必须上 MQ，见设计文档 §7 / §8 |
| **语义是什么** | **at-least-once**：不丢，但会重复。重复窗口 = 租约 TTL（60s），可解释、可查（`notify_delivery_stale_results_total`） |
| **哪些没做** | MQ、分布式锁、幂等去重表（伪 exactly-once）、模板引擎/可视化后台/RBAC、全链路追踪、批量重放、顺序保证。每条都写了"翻案条件"（设计文档 §6.2） |

## 快速启动

```bash
docker compose up -d              # 1. MySQL 8.0.46（localhost:33061），Flyway 自动建表
mvn -DskipTests package           # 2. 打包
java -jar target/rc-xin.jar       # 3. 启动，8080
curl -s http://localhost:8080/actuator/health    # -> {"status":"UP"}
```

想看它到底能干什么，直接跑 5 分钟 Demo（自己清库、起 mock、起实例，不需要手工改配置）：

```bash
bash scripts/demo.sh              # 约 90 秒：幂等 → 成功 → 脱敏 → 失败分级 → 死信 → 重放 → 指标
```

跑测试（**需要 Docker**，集成测试的 MySQL 由 Testcontainers 提供，不需要本机装库）：

```bash
mvn verify                        # 166 个用例，0 skipped
```

## 架构

```
 POST /v1/notifications
   │  API Key → caller 派生 · 背压(503+Retry-After) · 参数校验 · SSRF 静态判定 · AES-GCM 加密
   ▼
 ┌─────────────────────────────────────────────┐
 │ notification 表（PENDING / RETRY_WAIT / …） │  ← 唯一事实来源，也是队列
 └─────────────────────────────────────────────┘
   ▲ 每 500ms：SELECT … FOR UPDATE SKIP LOCKED + 租约（lease_owner = 实例/轮次）
   │ batch = min(100, 空闲 worker)
   ▼
 worker 池 200：SSRF 逐 IP 复检 → 解密 → HTTP（每端点并发配额 + 熔断 + 半开探针）
   │
   ├─ 2xx          → SUCCEEDED
   ├─ 5xx / 超时   → RETRY_WAIT（指数退避 + 全抖动，Retry-After 优先）
   ├─ 4xx / 3xx    → DEAD（PERMANENT，一次即死）
   └─ 预算耗尽      → DEAD（BUDGET_EXHAUSTED）
        ↓ 明细与状态落库都带租约守卫（旧结果不许覆盖新状态），死信只有一个宣布者
```

## 实测（T4.2，150 QPS × 60s，12 个下游端点）

| 场景 | p50 | p90 | p99 | 吞吐 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 纯受理（关投递） | 3.2ms | 7.0ms | **16.9ms** | 150/s | 全部 202 |
| 全链路，下游 ~1ms | 3.2ms | 16.2ms | **41.3ms** | 150/s | 8250 单全部 SUCCEEDED，积压排空 |
| 全链路，下游 200ms | 3.2ms | 13.1ms | **35.3ms** | 150/s | 8250 单全部 SUCCEEDED；worker 均值 26.4/200 |

三个自己测出来的修正（细节见 `docs/T4-交付.md`）：

1. **每单是 4 次写，不是 2 次**（受理 INSERT + 认领 UPDATE + 明细 INSERT + 终态 UPDATE），
   实测 600 行/秒 = 150×4；设计文档 §7 的"350 行/秒、余量 9 倍"已改成"690 行/秒、余量 4.3 倍"。
2. **满负荷下 p99 由 fsync 决定，不是代码路径**：p50 全程不动，关掉投递 p99 掉回 17ms，
   GC 最大暂停仅 9.5ms。本机 Docker 卷单次提交 ~3ms；真机 NVMe 或做批量提交才能真正压住。
3. **单端点吞吐 ≈ 许可数 × 轮询频率**：150 QPS 全压一个端点时只能消化 ~57/s（`max-concurrent-per-endpoint=16`）。
   真实部署 150 QPS 摊在多家供应商上没问题；单供应商占大头时要把这个值提到 `QPS × 平均延迟` 以上。

复现：`bash scripts/loadtest.sh --rate 150 --duration 60`（自带的 Java 压测器，不需要 k6/wrk）。

## 里程碑

- [x] **M0** 骨架可启动、可迁移、可测试（T0.1 结构 / T0.2 建表 / T0.3 启动自检）
- [x] **M1** 能存能投（T1.1 接入接口 / T1.2 投递引擎 / T1.3 幂等去重）
- [x] **M2** 可靠投递（T2.1 失败分级 / T2.2 租约恢复 / T2.3 死信终态 / T2.4 出站安全 / T2.5 端点隔离与熔断）
- [x] **M3** 可观测与运维（T3.1 查询 / T3.2 重放 / T3.3 指标·背压·归档）
- [x] **M4** 交付（T4.1 Testcontainers 集成测试 / T4.2 压测与数据回填 / T4.3 README + Demo / T4.4 AI 会话归档）

## 演示脚本

```bash
# 5 分钟全流程：幂等 → 成功 → 脱敏 → 500/400 分级 → 死信 → 重放 → 指标（约 90s）
bash scripts/demo.sh

# 投递中途 kill -9 一个实例，另一个实例在租约到期后接手（约 70s，含 60s 租约等待）
bash scripts/crash_recovery_demo.sh

# 恒 502 下游 + max_attempts=1：一次即 DEAD 与死信日志（约 15s）
bash scripts/dead_letter_demo.sh

# 出站安全：metadata/内网地址被拒 422、凭证密文入库、投递期拦下绕过接入的内网目标（约 10s）
bash scripts/ssrf_demo.sh

# 端点隔离：坏端点熔断后只推迟不烧预算，好端点不受影响（约 25s）
bash scripts/circuit_breaker_demo.sh

# 查询接口：逐次明细、Header 脱敏、body 不回显、越权 404（约 15s）
bash scripts/query_demo.sh

# 人工重放：DEAD 回队列、attempt_count 不归零、预算追加一档、重复点击 409（约 15s）
bash scripts/replay_demo.sh

# 指标 / 背压 / 归档：抓指标、积压超阈值 503 + Retry-After、清密文但保留行（约 25s）
bash scripts/observability_demo.sh

# 150 QPS 压测：受理 p99 / DB 写入速率 / worker 利用率（约 90s）
bash scripts/loadtest.sh --rate 150 --duration 60

# 手工验收用的下游
python3 scripts/mock_downstream.py --port 9001 --sleep 20     # 触发 15s 超时
python3 scripts/mock_downstream.py --port 9001 --status 500   # 触发可重试失败
python3 scripts/mock_downstream.py --port 9001 --quiet        # 压测时用（逐条打印会成为瓶颈）
```

## 接口

```bash
# 提交（幂等键唯一：同 caller 同键重复提交返回原单）
curl -s -X POST http://localhost:8080/v1/notifications \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' -d '{
    "idempotency_key": "order_paid:pay_001",
    "target_url": "https://crm.example.com/cb",
    "headers": {"Authorization": "Bearer xxx"},
    "body": "{\"order_id\":\"o_1\"}"
  }'

# 单号详情：状态 + 每轮 attempt（状态码/耗时/错误分类/响应片段）；Header 只回显白名单，body 只回答有无
curl -s 'http://localhost:8080/v1/notifications/1' -H 'X-Api-Key: k_shop'

# 死信清单：键集分页，next_cursor 为 null 即到底
curl -s 'http://localhost:8080/v1/notifications?status=DEAD&limit=20' -H 'X-Api-Key: k_shop'

# 人工重放（reason 必填）：仅 DEAD 可重放，attempt_count 不归零、预算追加一档
curl -s -X POST 'http://localhost:8080/v1/notifications/1/replay' \
  -H 'Content-Type: application/json' -H 'X-Api-Key: k_shop' \
  -d '{"operator":"zhang.san","reason":"供应商已恢复，重新投递"}'
```

## 监控

```bash
# 14 个 notify_* 指标：受理 QPS/错误率、幂等命中、背压拒绝、队列深度/在途/死信、队头逾期秒数、
# 投递结果（按端点）、跳过、过期结果、死信、熔断端点数、归档清理量、worker 占用
curl -s http://localhost:8080/actuator/prometheus | grep '^notify_'
```

成功率由原始计数算出，不额外存 Gauge（窗口是查询时的事）：

```promql
sum(rate(notify_delivery_attempts_total{result="success"}[5m]))
  / sum(rate(notify_delivery_attempts_total[5m]))
```

告警建议看这三条：`notify_queue_oldest_overdue_seconds`（队列健康度主指标）、
`notify_delivery_stale_results_total`（双投正在发生的证据）、`notify_circuit_open_endpoints`（某供应商在挂）。

## 配置

全部参数收口在 `application.yml` 的 `notify.*`，启动自检（`StartupGuard`）会在约束被破坏时**拒绝启动**：
租约 > HTTP 总超时 + 余量、批大小 ≤ worker 池、单端点并发 ≤ worker 池、跳过延迟 > 0 等。

生产必须注入的两项：

```bash
export NOTIFY_CRYPTO_KEY=$(openssl rand -base64 32)   # 仓库内置的是开发密钥，启动会 WARN
export NOTIFY_SSRF_ALLOWED_HOSTS=                     # 空 = 禁止任何内网/回环目标（默认）
```

## 文档

| 文档 | 内容 |
| --- | --- |
| `docs/T2.2-租约与崩溃恢复.md` | 租约、旧结果守卫、kill -9 后的接手 |
| `docs/T2.3-死信终态与尝试预算.md` | 三级分类、退避、单一宣布者 |
| `docs/T2.4-出站安全与凭证加密.md` | SSRF 两层校验、AES-GCM 的 AAD 设计 |
| `docs/T2.5-端点隔离与熔断.md` | 端点粒度、许可 + 熔断、跳过为什么退预算 |
| `docs/T3.1-查询接口与逐次明细.md` | 键集分页、Header 白名单、body 不出库 |
| `docs/T3.2-人工重放.md` | 守卫写在 WHERE、预算追加一档、审计同事务 |
| `docs/T3.3-指标背压与归档.md` | 抓取不查库、按年龄而非创建时间、只清载荷不删行 |
| `docs/T4-交付.md` | Testcontainers 决策、压测方法与三个发现、Demo 剧本、已知边界 |
| `docs/ai-session.zip` | AI 建议 → 我的判断 → 最终决定（8 条链 + 未采纳清单 + 原始提问） |
