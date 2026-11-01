package com.rc.notify.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

/**
 * 投递引擎的存储侧（T1.2）：认领 / 回读 / 状态落库 / 逐次明细。
 *
 * <p>SQL 与设计文档 V2 §4.3 一一对应，两个刻意的边界：
 * <ul>
 *   <li>{@link #claim} 是短事务（UPDATE + 回读），HTTP 调用绝不进来——行锁随事务提交释放，
 *       否则一条 15s 慢请求挂住整批行锁和连接（T1.2 文档 §2.1）；</li>
 *   <li>状态落库带 {@code lease_owner} 守卫，attempt 明细不带——明细是事实记录，
 *       无论守卫成败都要留痕（T1.2 文档 §2.5）。</li>
 * </ul>
 *
 * <p>{@code leaseOwner} 传入的是"实例ID/轮次token"（见 Dispatcher），认领后回读与守卫都以它定位本轮行。
 */
@Repository
public class ClaimRepository {

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 认领：状态置 PROCESSING、写租约、attempt_count 在认领时自增（§4.2：worker 崩溃后没人执行"完成后自增"）。 */
    private static final String CLAIM_UPDATE = """
            UPDATE notification
            SET status        = 'PROCESSING',
                lease_owner   = ?,
                lease_until   = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                attempt_count = attempt_count + 1
            WHERE id IN (
              SELECT id FROM (
                SELECT id FROM notification
                WHERE (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at <= NOW(3))
                   OR (status = 'PROCESSING' AND lease_until < NOW(3))
                ORDER BY next_attempt_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
              ) t
            )
            """;

    /** 回读：轮次 token 保证只命中本轮认领的行，不会捞到上一轮仍在途的任务（T1.2 文档 §2.2）。 */
    private static final String CLAIM_SELECT = """
            SELECT id, caller_id, target_url, http_method, headers_enc, body_enc, attempt_count, max_attempts
            FROM notification
            WHERE lease_owner = ? AND status = 'PROCESSING'
            """;

    private static final String MARK_SUCCEEDED = """
            UPDATE notification
            SET status = 'SUCCEEDED', last_status_code = ?, last_error_class = NULL,
                lease_owner = NULL, lease_until = NULL
            WHERE id = ? AND lease_owner = ? AND status = 'PROCESSING'
            """;

    private static final String MARK_FAILED = """
            UPDATE notification
            SET status = 'RETRY_WAIT', next_attempt_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                last_status_code = ?, last_error_class = ?,
                lease_owner = NULL, lease_until = NULL
            WHERE id = ? AND lease_owner = ? AND status = 'PROCESSING'
            """;

    /**
     * 死信落库（T2.3）。故意<b>不动 next_attempt_at</b>。
     *
     * <p>DEAD 行不会再被调度，改写 next_attempt_at 只会把一个"永远不会被认领的时刻"混进
     * idx_due 的区间里，让"队列里还有多少到期任务"这类查询需要额外过滤。保留最后一次计划投递时刻，
     * 复盘时还能回答"它本来该在什么时候再试一次"。死信时间看 updated_at。
     */
    private static final String MARK_DEAD = """
            UPDATE notification
            SET status = 'DEAD',
                last_status_code = ?, last_error_class = ?,
                lease_owner = NULL, lease_until = NULL
            WHERE id = ? AND lease_owner = ? AND status = 'PROCESSING'
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO delivery_attempt
                (notification_id, attempt_no, started_at, duration_ms, status_code, error_class, response_snippet)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 跳过退还（T2.5）：熔断中或端点并发已满时，把已认领的任务原样放回队列。
     *
     * <p>三个要点，每一个写错都出事：
     * <ul>
     *   <li><b>{@code attempt_count = attempt_count - 1}</b>——认领时自增过（T1.2：worker 崩溃后没人执行
     *       "完成后自增"），这里必须退回来。否则供应商挂 30 分钟，排队中的健康任务会被我们自己烧成 DEAD：
     *       明明是对方的问题，却耗光我方重试预算，等对方恢复时任务已经死了（设计文档 §4.5）；</li>
     *   <li><b>不写 {@code delivery_attempt}</b>——这次 HTTP 一个字节都没发出，写明细既不是事实，
     *       还会埋一个唯一键冲突：{@code attempt_no} 等于退还后的 {@code attempt_count}，下次认领会用
     *       同一个号，{@code uk_n_attempt} 直接撞车，任务卡在 PROCESSING 直到租约过期；</li>
     *   <li><b>带 {@code lease_owner} 守卫</b>——与状态落库同一条规矩：租约已被他人接管时不碰那行。</li>
     * </ul>
     *
     * <p>状态写 {@code RETRY_WAIT} 而不是 {@code PENDING}：调度上两者等价（认领条件同时包含），
     * 但"已经有过尝试"的行标成 PENDING 会误导排查。退还后"到底试过几次"看 {@code attempt_count}，
     * 而不是看状态——这是本系统唯一一处"状态=RETRY_WAIT 但没有真的尝试"的情形。
     */
    private static final String RELEASE_SKIPPED_TEMPLATE = """
            UPDATE notification
            SET status        = 'RETRY_WAIT',
                attempt_count = attempt_count - 1,
                next_attempt_at = DATE_ADD(NOW(3), INTERVAL ? SECOND),
                lease_owner   = NULL,
                lease_until   = NULL
            WHERE lease_owner = ? AND status = 'PROCESSING' AND attempt_count > 0
              AND id IN (%s)
            """;

    /**
     * 认领一批到期（或租约过期）任务。短事务：UPDATE + 回读，行锁随提交释放。
     *
     * @param leaseOwner 实例ID/轮次token，同时是后续状态落库的守卫键
     * @param batch      认领条数（调用方已按空闲 worker 钳制）
     * @param leaseTtlS  租约秒数（DB NOW(3) 起算，不用应用时钟——§4.3 约束 3）
     */
    @Transactional
    public List<Claimed> claim(String leaseOwner, int batch, int leaseTtlS) {
        jdbc.update(CLAIM_UPDATE, leaseOwner, leaseTtlS, batch);
        return jdbc.query(CLAIM_SELECT, (rs, i) -> new Claimed(
                        rs.getLong("id"),
                        rs.getString("caller_id"),
                        rs.getString("target_url"),
                        rs.getString("http_method"),
                        rs.getBytes("headers_enc"),
                        rs.getBytes("body_enc"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts")),
                leaseOwner);
    }

    /** 成功落库。返回 false = 守卫失败（结果已过期），调用方丢弃并记日志。 */
    public boolean markSucceeded(long id, String leaseOwner, Integer statusCode) {
        return jdbc.update(MARK_SUCCEEDED, statusCode, id, leaseOwner) == 1;
    }

    /**
     * 可重试失败落库：状态改为 RETRY_WAIT 并排下一次到期时刻。
     *
     * @param delayS   退避秒数（T2.1：指数退避 + 全抖动，或对端 Retry-After）
     * @param statusCode 本次 HTTP 状态码，网络层失败为 null
     * @param errorClass 失败类别（由 ErrorClassifier 判定，与晋级决策同源——T2.3）
     * @return false = 守卫失败（租约已转移），调用方丢弃结果
     */
    public boolean markFailed(long id, String leaseOwner, long delayS,
                              Integer statusCode, String errorClass) {
        return jdbc.update(MARK_FAILED, delayS, statusCode, errorClass, id, leaseOwner) == 1;
    }

    /**
     * 死信落库（T2.3）：状态改为终态 DEAD，清租约，不动 next_attempt_at（见 MARK_DEAD 注释）。
     *
     * @return false = 守卫失败（租约已转移，结果过期），调用方丢弃且<b>不得</b>发死信事件
     */
    public boolean markDead(long id, String leaseOwner, Integer statusCode, String errorClass) {
        return jdbc.update(MARK_DEAD, statusCode, errorClass, id, leaseOwner) == 1;
    }

    /**
     * 跳过退还（T2.5）：把一批"没有真正投递"的任务放回队列，并<b>退回尝试预算</b>。
     *
     * <p>批量形态的存在理由很实际：熔断期间每一轮 poll 都会认领到一批同端点的行，
     * 逐条退还是 N 次 UPDATE；一次 {@code id IN (...)} 就够，写放大差两个数量级。
     *
     * @param ids    本轮的这批任务（全部属于调用方持有的 leaseOwner）
     * @param delayS 推迟秒数（熔断中较长，并发满时较短，均带抖动）
     * @return 真正退还的行数；小于 ids.size() 说明部分行的租约已被接管（正常竞争，不是错误）
     */
    public int releaseSkipped(String leaseOwner, List<Long> ids, long delayS) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Object[] args = new Object[ids.size() + 2];
        args[0] = delayS;
        args[1] = leaseOwner;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 2] = ids.get(i);
        }
        return jdbc.update(RELEASE_SKIPPED_TEMPLATE.formatted(placeholders), args);
    }

    /** 逐次明细：只追加、无条件插入（无论守卫成败，这次 HTTP 真实发生过）。 */
    public void insertAttempt(long notificationId, int attemptNo, Timestamp startedAt, long durationMs,
                              Integer statusCode, String errorClass, String responseSnippet) {
        jdbc.update(con -> {
            var ps = con.prepareStatement(INSERT_ATTEMPT);
            ps.setLong(1, notificationId);
            ps.setInt(2, attemptNo);
            ps.setTimestamp(3, startedAt);
            ps.setLong(4, durationMs);
            if (statusCode == null) {
                ps.setNull(5, Types.SMALLINT);
            } else {
                ps.setInt(5, statusCode);
            }
            if (errorClass == null) {
                ps.setNull(6, Types.VARCHAR);
            } else {
                ps.setString(6, errorClass);
            }
            if (responseSnippet == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, responseSnippet);
            }
            return ps;
        });
    }

    /** 认领后的任务视图（attemptNo = 自增后的 attempt_count，delivery_attempt 的 attempt_no 与之一致）。 */
    public record Claimed(
            /** 通知单号（notification.id）。 */
            long id,
            /** 调用方标识；本层只用于日志与排障，不参与投递。 */
            String callerId,
            /** 目标地址（接入时已做协议与结构校验）。 */
            String targetUrl,
            /** 大写 HTTP 方法（接入时已过白名单）。 */
            String httpMethod,
            /** 加密后的 Header 字节；投递前由 PayloadCodec 解密，可为 null（无自定义头）。 */
            byte[] headersEnc,
            /** 加密后的请求体字节；可为 null（GET/DELETE 等无体请求）。 */
            byte[] bodyEnc,
            /** 本次尝试序号，1 起；已含本次认领的自增，直接用于退避计算与明细落库。 */
            int attemptNo,
            /** 该条通知的重试预算（调用方覆盖值或全局默认），达到即转 DEAD。 */
            int maxAttempts
    ) {}
}
