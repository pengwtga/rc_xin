package com.rc.notify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 人工重放的存储侧（T3.2）：状态迁移 + 审计落库，两者在同一个事务里。
 *
 * <p>放同一个事务是<b>fail-closed</b>：审计写不进去，状态就不许迁移——"没有记录的重放"
 * 比"重放失败"更糟，后者重试一下就好，前者是一条无法追溯的操作。
 * （对照投递侧的取舍：attempt 明细是无条件插入、状态落库才是关键——那里的两件事不是
 * "动作与它的审计"，所以不绑事务。）
 */
@Repository
public class ReplayRepository {

    /**
     * 状态守卫只认 {@code status = 'DEAD'}，这一条同时解决了三件事：
     * <ol>
     *   <li>防重复点击——第一次重放已把状态改成 PENDING，第二次 UPDATE 匹配不到行（0 行受影响）；</li>
     *   <li>防并发双击——两个请求同时在 UPDATE 上排队，InnoDB 行锁串行化，后提交者读到的是
     *       新状态、守卫失败；</li>
     *   <li>防对非终态误操作——PENDING/RETRY_WAIT/PROCESSING/SUCCEEDED/CANCELED 一律拒绝。</li>
     * </ol>
     * 不需要分布式锁，也不需要 SELECT ... FOR UPDATE：单行 UPDATE 的原子性已经够了。
     *
     * <p>刻意<b>不动</b> attempt_count（历史）与 last_status_code/last_error_class
     * （最后一次投递的事实，重放后仍能看出"它之前是被什么打死的"）。
     */
    private static final String TRANSITION = """
            UPDATE notification
            SET status          = 'PENDING',
                max_attempts    = ?,
                next_attempt_at = NOW(3),
                lease_owner     = NULL,
                lease_until     = NULL
            WHERE id = ? AND caller_id = ? AND status = 'DEAD'
            """;

    private static final String INSERT_LOG = """
            INSERT INTO replay_log
                (notification_id, operator, reason, attempt_count_before, max_attempts_after)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_NOTIFICATION = """
            SELECT operator, reason, attempt_count_before, max_attempts_after, created_at
            FROM replay_log
            WHERE notification_id = ?
            ORDER BY id
            """;

    private final JdbcTemplate jdbc;

    public ReplayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * DEAD → PENDING + 写审计，一个事务。
     *
     * @return false = 状态守卫失败（不是 DEAD / 不是这个 caller 的单 / 已被重放过）
     */
    @Transactional
    public boolean replayToPending(long id, String callerId, int newMaxAttempts,
                                   String operator, String reason, int attemptCountBefore) {
        int updated = jdbc.update(TRANSITION, newMaxAttempts, id, callerId);
        if (updated != 1) {
            return false;
        }
        jdbc.update(INSERT_LOG, id, operator, reason, attemptCountBefore, newMaxAttempts);
        return true;
    }

    /** 某条通知的重放历史，按时间正序（供查单详情的 replays 字段）。 */
    public List<ReplayRow> findReplays(long notificationId) {
        return jdbc.query(FIND_BY_NOTIFICATION, REPLAY_MAPPER, notificationId);
    }

    private static final RowMapper<ReplayRow> REPLAY_MAPPER = (rs, i) -> new ReplayRow(
            rs.getString("operator"),
            rs.getString("reason"),
            rs.getInt("attempt_count_before"),
            rs.getInt("max_attempts_after"),
            NotificationReadRepository.utc(rs, "created_at"));

    /** 重放审计行（时间由 Mapper 统一转成 UTC OffsetDateTime）。 */
    public record ReplayRow(
            String operator, String reason,
            int attemptCountBefore, int maxAttemptsAfter,
            java.time.OffsetDateTime createdAt) {}
}
