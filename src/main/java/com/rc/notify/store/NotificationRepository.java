package com.rc.notify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;

/**
 * notification 表的写入侧（T1.1）。
 *
 * <p>两个刻意的设计点：
 * <ul>
 *   <li>{@code next_attempt_at = NOW(3)} —— 用数据库时钟，多实例不依赖应用时钟（T1.1 文档 §2.3）；</li>
 *   <li>只做 INSERT 一件事 —— 查询/状态流转属 T1.2 与 T3.1，避免职责膨胀。</li>
 * </ul>
 */
@Repository
public class NotificationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO notification
                (caller_id, idempotency_key, target_url, http_method,
                 headers_enc, body_enc, status, attempt_count, max_attempts, next_attempt_at)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, NOW(3))
            """;

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条待投递通知，返回自增 id（即对外 notification_no）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 违反 uk_idem（同 caller 同幂等键）
     */
    public long insert(NewNotification n) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT_SQL, new String[]{"id"});
            ps.setString(1, n.callerId());
            ps.setString(2, n.idempotencyKey());
            ps.setString(3, n.targetUrl());
            ps.setString(4, n.httpMethod());
            setNullableBytes(ps, 5, n.headers());
            setNullableBytes(ps, 6, n.body());
            ps.setInt(7, n.maxAttempts());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 notification 未返回自增 id");
        }
        return key.longValue();
    }

    private static void setNullableBytes(PreparedStatement ps, int index, byte[] value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARBINARY);
        } else {
            ps.setBytes(index, value);
        }
    }

    /** 插入所需的最小字段集（不含服务端生成的 status/时间/租约）。 */
    public record NewNotification(
            String callerId,
            String idempotencyKey,
            String targetUrl,
            String httpMethod,
            byte[] headers,
            byte[] body,
            int maxAttempts
    ) {}

    /**
     * 幂等命中后回查原单（T1.3）。
     *
     * <p>走 {@code uk_idem (caller_id, idempotency_key)} 覆盖索引点查。
     * 调用时机是"INSERT 已抛 DuplicateKeyException"之后——此刻原单必然已提交
     * （InnoDB 唯一索引插入会等待未提交的冲突键），所以正常情况不会返回空
     * （T1.3 文档 §2.6）。
     */
    public Optional<Existing> findExisting(String callerId, String idempotencyKey) {
        return jdbc.query(
                        "SELECT id, status FROM notification WHERE caller_id = ? AND idempotency_key = ?",
                        (rs, i) -> new Existing(rs.getLong("id"), rs.getString("status")),
                        callerId, idempotencyKey)
                .stream().findFirst();
    }

    /** 已存在的原单：单号 + 当前状态（快照，不承诺后续不变）。 */
    public record Existing(long id, String status) {}
}
