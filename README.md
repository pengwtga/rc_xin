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
- [ ] M2 可靠投递（T2.1 失败分级 ✔ / T2.2 租约恢复 ✔ / T2.3 死信终态 ✔ / T2.4 出站安全 ✔ / T2.5 熔断隔离）
- [ ] M3 可观测与运维（查询 / 重放 / 指标 / 背压 / 归档）
- [ ] M4 交付（压测 / README / AI 会话记录）

## 故障演示

```bash
# 投递中途 kill -9 一个实例，另一个实例在租约到期后接手（约 70s，含 60s 租约等待）
bash scripts/crash_recovery_demo.sh

# 一条通知打到恒 502 的下游，观察 max_attempts=1 直接进 DEAD 与死信日志（约 15s）
bash scripts/dead_letter_demo.sh

# 出站安全：metadata/内网地址被拒 422、凭证密文入库、投递期拦下绕过接入的内网目标（约 10s）
bash scripts/ssrf_demo.sh

# 慢/坏下游，供手工验收
python3 scripts/mock_downstream.py --port 9001 --sleep 20   # 触发 15s 超时
python3 scripts/mock_downstream.py --port 9001 --status 500 # 触发可重试失败
```

原理与取舍见 `docs/T2.2-租约与崩溃恢复.md`、`docs/T2.3-死信终态与尝试预算.md`、`docs/T2.4-出站安全与凭证加密.md`。

## 配置

全部参数收口在 `application.yml` 的 `notify.*`，启动自检（`StartupGuard`）保证：
租约 > HTTP 总超时 + 余量、批大小 ≤ worker 池等约束被破坏时**拒绝启动**。
