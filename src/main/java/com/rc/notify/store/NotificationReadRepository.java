package com.rc.notify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 查询侧读取（T3.1）—— 与 {@link NotificationRepository}（写入侧）、{@link ClaimRepository}（投递侧）并列。
 *
 * <p>三个 Repository 分开不是形式主义：这三处的读写模式完全不同（写入是单行 INSERT、投递是
 * 锁行 UPDATE、查询是索引区间扫描），混在一个类里最先失控的是<b>事务与索引的推理</b>——
 * 你不知道某个方法会不会顺手加个锁。
 *
 * <p>全部方法都带 {@code caller_id} 条件，<b>不是可选项</b>：租户隔离不能依赖 Service 层记得加参数，
 * 底层 SQL 自己就不给"跨租户查询"这条路径。
 */
@Repository
public class NotificationReadRepository {

    /**
     * 详情查询。
     *
     * <p>{@code body_enc IS NOT NULL AS has_body} 是刻意的：查询只需要回答"有没有 body"，
     * 不必把最长 64KB 的 MEDIUMBLOB 从磁盘读进内存再丢掉。headers 则必须取出密文——
     * 连名字都在密文里，不解就列不出来。
     */
    private static final String FIND_ONE = """
            SELECT id, status, idempotency_key, target_url, http_method,
                   attempt_count, max_attempts, next_attempt_at,
                   last_status_code, last_error_class, created_at, updated_at,
                   headers_enc, (body_enc IS NOT NULL) AS has_body
            FROM notification
            WHERE id = ? AND caller_id = ?
            """;

    /** 逐次明细：走 uk_n_attempt(notification_id, attempt_no) 的前缀，天然有序。 */
    private static final String FIND_ATTEMPTS = """
            SELECT attempt_no, started_at, duration_ms, status_code, error_class, response_snippet
            FROM delivery_attempt
            WHERE notification_id = ?
            ORDER BY attempt_no DESC
            LIMIT ?
            """;

    /** 列表基础形态；status / cursor 两个条件按需拼接（见 {@link #page}）。 */
    private static final String PAGE_BASE = """
            SELECT id, status, idempotency_key, target_url, http_method,
                   attempt_count, max_attempts, next_attempt_at,
                   last_status_code, last_error_class, created_at, updated_at
            FROM notification
            WHERE caller_id = ?
            """;

    private final JdbcTemplate jdbc;

    public NotificationReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按单号 + 调用方查一条；查不到与"不是你的"返回同一个空值（调用方转 404）。 */
    public Optional<DetailRow> find(long id, String callerId) {
        return jdbc.query(FIND_ONE, DETAIL_MAPPER, id, callerId).stream().findFirst();
    }

    /**
     * 取某条通知的尝试明细，返回<b>按 attemptNo 升序</b>的最近 {@code maxRows} 条。
     *
     * <p>SQL 里倒序 + LIMIT 再在内存里翻转，而不是直接正序 LIMIT：明细条数理论上界是
     * max_attempts（含重放追加的预算），但一条被人工反复重放的单可以很长。
     * 排障时"最近几次怎么失败的"比"最早那次怎么失败的"有用得多，所以截断的是头部。
     */
    public List<AttemptRow> findAttempts(long notificationId, int maxRows) {
        List<AttemptRow> desc = jdbc.query(FIND_ATTEMPTS, ATTEMPT_MAPPER, notificationId, maxRows);
        return desc.reversed();
    }

    /**
     * 键集分页（keyset）：
     * <pre>
     *   WHERE caller_id=? [AND status=?] [AND id&lt;?] ORDER BY id DESC LIMIT n+1
     * </pre>
     *
     * <p><b>为什么不用 OFFSET</b>：{@code LIMIT 100000, 20} 要求数据库先数过前 10 万行，
     * 越翻越慢；更致命的是翻页期间有新单插入时，OFFSET 指向的位置会整体后移，
     * 客户端看到重复行或漏行。键集用"上一页最后一条的单号"做锚点，这两个问题都不存在。
     *
     * <p>条件用字符串拼接而不是 {@code (status = ? OR ? IS NULL)}：后者能让优化器放弃
     * idx_caller_status，退化成扫描该 caller 的全部行。
     *
     * @param status    状态过滤，null 表示不过滤
     * @param beforeId  游标（返回 id 严格小于它的行），null 表示第一页
     * @param pageSize  本页条数
     */
    public List<SummaryRow> page(String callerId, String status, Long beforeId, int pageSize) {
        StringBuilder sql = new StringBuilder(PAGE_BASE);
        List<Object> args = new java.util.ArrayList<>(4);
        args.add(callerId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (beforeId != null) {
            sql.append(" AND id < ?");
            args.add(beforeId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(pageSize + 1);   // 多取一条：判断"还有没有下一页"，避免再发一次 COUNT
        return jdbc.query(sql.toString(), SUMMARY_MAPPER, args.toArray());
    }

    // ——— 行映射 ———

    private static final RowMapper<DetailRow> DETAIL_MAPPER = (rs, i) -> new DetailRow(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getString("target_url"),
            rs.getString("http_method"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            utc(rs, "next_attempt_at"),
            nullableInt(rs, "last_status_code"),
            rs.getString("last_error_class"),
            utc(rs, "created_at"),
            utc(rs, "updated_at"),
            rs.getBytes("headers_enc"),
            rs.getInt("has_body") == 1);

    private static final RowMapper<SummaryRow> SUMMARY_MAPPER = (rs, i) -> new SummaryRow(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getString("target_url"),
            rs.getString("http_method"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            utc(rs, "next_attempt_at"),
            nullableInt(rs, "last_status_code"),
            rs.getString("last_error_class"),
            utc(rs, "created_at"),
            utc(rs, "updated_at"));

    private static final RowMapper<AttemptRow> ATTEMPT_MAPPER = (rs, i) -> new AttemptRow(
            rs.getInt("attempt_no"),
            utc(rs, "started_at"),
            (Long) rs.getObject("duration_ms", Long.class),
            nullableInt(rs, "status_code"),
            rs.getString("error_class"),
            rs.getString("response_snippet"));

    /**
     * DATETIME(3) → UTC 的 OffsetDateTime。
     *
     * <p>时间列里存的是 UTC 但不带时区信息，连接串上必须固定 {@code serverTimezone=UTC}
     * （application.yml 已固定），否则驱动会按 JVM 默认时区解释，返回值整体偏移 8 小时——
     * 这类错误在跨时区部署时才暴露，所以这里统一补上 {@code Z} 而不是返回裸 LocalDateTime，
     * 让客户端不可能误读。
     */
    private static OffsetDateTime utc(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    // ——— 行对象：只在本包内流转，转成 DTO 由 query 包负责 ———

    /** 详情行。{@code headersEnc} 是密文原样，脱敏在 query 层做。 */
    public record DetailRow(
            long id, String status, String idempotencyKey, String targetUrl, String httpMethod,
            int attemptCount, int maxAttempts, OffsetDateTime nextAttemptAt,
            Integer lastStatusCode, String lastErrorClass,
            OffsetDateTime createdAt, OffsetDateTime updatedAt,
            byte[] headersEnc, boolean hasBody) {}

    /** 列表行。 */
    public record SummaryRow(
            long id, String status, String idempotencyKey, String targetUrl, String httpMethod,
            int attemptCount, int maxAttempts, OffsetDateTime nextAttemptAt,
            Integer lastStatusCode, String lastErrorClass,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    /** 明细行。 */
    public record AttemptRow(
            int attemptNo, OffsetDateTime startedAt, Long durationMs,
            Integer statusCode, String errorClass, String responseSnippet) {}
}
