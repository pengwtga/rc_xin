-- V2__query_index.sql —— T3.1 查询接口的索引（设计文档 §5 / §4.11）
--
-- 为什么不直接改 V1：V1 已推送到远端仓库并被执行过，Flyway 的 checksum 校验会拒绝
-- 任何内容改动（改注释也会变 checksum）。建表之后再补索引，正确做法就是新增一个版本。
--
-- 只加一条索引，因为它只服务一条确定的查询路径：
--
--   WHERE caller_id=? AND status=? AND id<? ORDER BY id DESC LIMIT n
--   死信清单（§5 的拉取式消费）与按状态巡检，是列表接口唯一的热路径。
--
-- 三列的角色各不相同：caller_id + status 等值定区间，id 提供反向扫描的序。
-- 副作用是这条索引天然覆盖了列表要返回的 id/status，回表都省了（EXPLAIN 显示 Using index）。
--
-- 刻意没给"无状态过滤的列表"也建一条 (caller_id, id)：
--   · 那是一次运维兜底用法（低频、可以慢一点），而每条二级索引都要在每个 INSERT 上付维护成本；
--   · 25 万行实测：不带状态过滤时优化器在 PRIMARY 反向扫描（0.06ms）与专用索引（0.10ms）之间切换，
--     量级上都没有问题。真到需要时再加，加之前先看 EXPLAIN。
-- 一条索引服务一条热路径，是这个表能保持写入快的前提——它同时还是投递队列。

ALTER TABLE notification
  ADD KEY idx_caller_status (caller_id, status) COMMENT '列表查询热路径：caller_id+status 等值定区间、id 反向扫描出序；覆盖索引，无回表无 filesort';
