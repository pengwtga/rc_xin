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
- [ ] M1 能存能投（接入接口 + 投递引擎 + 幂等）
- [ ] M2 可靠投递（失败分级 / 租约恢复 / 死信 / SSRF / 熔断隔离）
- [ ] M3 可观测与运维（查询 / 重放 / 指标 / 背压 / 归档）
- [ ] M4 交付（压测 / README / AI 会话记录）

## 配置

全部参数收口在 `application.yml` 的 `notify.*`，启动自检（`StartupGuard`）保证：
租约 > HTTP 总超时 + 余量、批大小 ≤ worker 池等约束被破坏时**拒绝启动**。
