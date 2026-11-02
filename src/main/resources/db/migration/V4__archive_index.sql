-- V4__archive_index.sql —— T3.3 归档任务的扫描索引（设计文档 §4.10）
--
-- 归档的两条查询是同一个形状：
--
--   WHERE status = ? AND updated_at < DATE_SUB(NOW(3), INTERVAL ? HOUR)
--     AND (body_enc IS NOT NULL OR headers_enc IS NOT NULL)
--   LIMIT ?
--
-- status 等值定区间，updated_at 提供范围，顺序不能反（范围列放前面会让等值失效）。
-- 这也说明为什么不能复用现成的三条：
--   · idx_due(status, next_attempt_at)      —— 第二列是投递时刻，不是变更时刻；
--   · idx_lease(status, lease_until)        —— 只服务 PROCESSING；
--   · idx_caller_status(caller_id, status)  —— 前缀是 caller，归档是全局扫描。
--
-- 已知代价（写在文档 T3.3 §5 里，不在这里偷偷优化）：
--   已被清空的行仍然满足前两个条件，每天会被索引范围重新扫到、再由第三个条件过滤掉。
--   这是"不引入归档水位表"的代价：单次运行是 O(该状态的老行数)，5000 万行量级约数秒，
--   发生在凌晨的低峰任务里可以接受。真到了不能接受的那天，正确做法是分区归档
--  （设计文档 §8 的 V1），而不是再加一张水位表——水位一旦丢了（重启、换实例）
--   还是要退回全扫，等于多了一个会失效的状态。

ALTER TABLE notification
  ADD KEY idx_status_updated (status, updated_at) COMMENT '归档扫描：status 等值 + updated_at 范围；仅服务 PII 清理这一条查询';
