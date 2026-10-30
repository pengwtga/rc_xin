-- V1__init.sql —— 设计文档 §4.1 数据模型（V2 版：无 replay_epoch，attempt_no 全局递增）

CREATE TABLE notification (
  id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '唯一单号，对外直接暴露（内部系统 + API Key 鉴权，无枚举风险）；不引入 ULID 等第二套标识',
  caller_id        VARCHAR(64)       NOT NULL COMMENT '调用方标识，由 API Key 解析；用于鉴权、幂等域、限流与数据隔离',
  idempotency_key  VARCHAR(128)      NOT NULL COMMENT '调用方填的事件级幂等键，建议 <事件类型>:<事件唯一ID>，如 order_paid:pay_001',
  target_url       VARCHAR(2048)     NOT NULL COMMENT '外部供应商 API 地址，落库前经 SSRF 校验',
  http_method      VARCHAR(8)        NOT NULL DEFAULT 'POST' COMMENT '白名单 POST/PUT/PATCH/DELETE/GET',
  headers_enc      VARBINARY(4096)   NULL     COMMENT '含凭证的 Header 经 AES-256-GCM 加密存储，密钥由部署环境注入；查询接口脱敏返回，日志不打印',
  body_enc         MEDIUMBLOB        NULL     COMMENT '请求体加密存储（MEDIUMBLOB：VARBINARY(65535) 会撑爆 InnoDB 65535 字节行上限，错误码 1118）；成功 24h / 失败 7d 后由归档任务清除（PII 最小留存）',
  status           VARCHAR(16)       NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RETRY_WAIT/PROCESSING/SUCCEEDED/DEAD/CANCELED',
  attempt_count    SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计尝试次数，认领时自增；达到 max_attempts 转 DEAD',
  max_attempts     SMALLINT UNSIGNED NOT NULL DEFAULT 10 COMMENT '重试预算，按调用方配置（默认 10）',
  next_attempt_at  DATETIME(3)       NOT NULL COMMENT '下次可投递时刻（UTC）；退避、熔断推迟都改这个字段',
  lease_owner      VARCHAR(64)       NULL     COMMENT '当前持有租约的实例 ID（排查用）',
  lease_until      DATETIME(3)       NULL     COMMENT '租约到期时刻（UTC）；过期即可被重新认领',
  last_error_class VARCHAR(32)       NULL     COMMENT 'TIMEOUT/DNS/TLS/CONN_REFUSED/HTTP_4XX/HTTP_5XX；告警路由依据',
  last_status_code SMALLINT          NULL     COMMENT '最近一次 HTTP 状态码',
  created_at       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (caller_id, idempotency_key) COMMENT '接入幂等：同调用方同业务键只允许一条',
  KEY idx_due   (status, next_attempt_at) COMMENT '到期任务扫描',
  KEY idx_lease (status, lease_until)      COMMENT '租约过期回收'
) ENGINE=InnoDB COMMENT='通知任务表 —— 本身即队列';

CREATE TABLE delivery_attempt (
  id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
  notification_id  BIGINT UNSIGNED   NOT NULL COMMENT '关联 notification.id',
  attempt_no       SMALLINT UNSIGNED NOT NULL COMMENT '全局第几次尝试（跨重放继续递增，与 attempt_count 一致，因此重放不会撞唯一键）',
  started_at       DATETIME(3)       NOT NULL,
  duration_ms      INT               NULL,
  status_code      SMALLINT          NULL COMMENT '网络层失败时为 NULL',
  error_class      VARCHAR(32)       NULL,
  response_snippet VARCHAR(2048)     NULL COMMENT '响应体前 2048 字符截断存储',
  created_at       DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_n_attempt (notification_id, attempt_no),
  KEY idx_n (notification_id)
) ENGINE=InnoDB COMMENT='逐次投递明细 —— 只追加，用于追责与排查';
