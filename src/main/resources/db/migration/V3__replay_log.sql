-- V3__replay_log.sql —— T3.2 人工重放的审计表（设计文档 §4.6）
--
-- 为什么是一张新表，而不是往 delivery_attempt 里塞一条"重放事件"：
--   delivery_attempt 的语义是"这次 HTTP 真实发生过"的事实记录，重放动作本身没有发出任何
--   HTTP——塞进去既不是事实，还会破坏"attempt_no 与 attempt_count 一致"这条不变式。
--   审计与事实分开存，两边各自的查询都干净（T3.1 文档 §6.1 承诺在这里兑现）。
--
-- 为什么必须有这张表，而不是只打一行日志：
--   重放是"人决定再试一次"的高风险动作。半年后复盘"这批 DEAD 是谁、为什么、给了多少预算
--   重放的"，日志会被轮转清掉，表不会。查询接口的 replays 字段直接读它，把 T3.1 状态轨迹
--   的三类时间线空洞（重放）补掉一类。

CREATE TABLE replay_log (
  id                  BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '自增主键，仅内部使用，不对外暴露',
  notification_id     BIGINT UNSIGNED   NOT NULL COMMENT '被重放的通知；不建外键（同 delivery_attempt：只追加表上约束成本高于收益）',
  operator            VARCHAR(64)       NOT NULL COMMENT '执行重放的操作人；缺省为调用方标识（系统级调用）',
  reason              VARCHAR(512)      NOT NULL COMMENT '重放原因，人工填写；必填——半年后没人记得"为什么把这批重放了"',
  attempt_count_before SMALLINT UNSIGNED NOT NULL COMMENT '重放时的累计尝试次数快照；attempt_count 不归零的证据',
  max_attempts_after  SMALLINT UNSIGNED NOT NULL COMMENT '重放后的预算（attempt_count_before + 当前档位），回答"这次给了多少次机会"',
  created_at          DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '重放时刻（UTC，DB 时钟）',
  PRIMARY KEY (id),
  KEY idx_n (notification_id) COMMENT '按单号回放重放历史：查单详情的 replays 字段走这条'
) ENGINE=InnoDB COMMENT='人工重放审计 —— 只追加；与投递事实(delivery_attempt)分开存';
