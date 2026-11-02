package com.rc.notify.archive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 归档的存储侧（T3.3）：挑批次 → 清密文 → 清明细里的响应片段。
 *
 * <p>三个方法的顺序不能换：必须先拿到 id 列表，再分别清两张表。反过来（先清 notification
 * 再回查 id）会漏掉一批明细——因为清完之后那些行已经不再匹配"body_enc 非空"的条件了。
 */
@Repository
public class ArchiveRepository {

    private final JdbcTemplate jdbc;

    public ArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取一批待清理的 id。
     *
     * <p>三个条件各自有用：
     * <ul>
     *   <li>{@code status = ?} + {@code updated_at < ...} 走 idx_status_updated，等值列在前、范围列在后；</li>
     *   <li>{@code (body_enc IS NOT NULL OR headers_enc IS NOT NULL)} 让"已经清过的行"不再被选中——
     *       这既是重跑安全的条件，也是循环能自然推进的原因（不清空就永远取到同一批）；</li>
     *   <li>没有 {@code ORDER BY}：本轮清哪 1000 行都行，加排序反而会让 MySQL 先把所有匹配行
     *       排一遍。索引顺序本身就是 (status, updated_at)，天然从最老的开始。</li>
     * </ul>
     */
    private static final String PICK_SQL = """
            SELECT id FROM notification
            WHERE status = ?
              AND updated_at < DATE_SUB(NOW(3), INTERVAL ? HOUR)
              AND (body_enc IS NOT NULL OR headers_enc IS NOT NULL)
            LIMIT ?
            """;

    /**
     * 清掉一批行的请求体与 Header 密文。
     *
     * <p><b>{@code updated_at = updated_at} 是故意的</b>：这一列是 {@code ON UPDATE CURRENT_TIMESTAMP}，
     * 不清空就会被归档动作刷新，而它的语义是"最后一次<b>状态</b>变更时刻"——归档不是状态变更。
     * 被污染之后，§4.11 里"这行多久没动过"的判断会失真（一批正常完成的单看起来像刚被处理过）。
     * 显式赋值后，MySQL 不再自动更新这一列。
     */
    private static final String CLEAR_PAYLOAD_TAIL = """
            UPDATE notification
            SET body_enc = NULL, headers_enc = NULL, updated_at = updated_at
            WHERE (body_enc IS NOT NULL OR headers_enc IS NOT NULL)
              AND id IN (%s)
            """;

    /**
     * 清掉这些单的响应片段。
     *
     * <p>snippet 来自下游响应，同样会回显手机号、订单号、甚至凭证（有的供应商把 token 回显在错误里）。
     * §4.10 说的是"PII 最小留存"，只清请求体不清响应体，等于留了半扇门。
     */
    private static final String CLEAR_SNIPPET_TAIL = """
            UPDATE delivery_attempt
            SET response_snippet = NULL
            WHERE response_snippet IS NOT NULL
              AND notification_id IN (%s)
            """;

    /**
     * @param status         目标终态（SUCCEEDED / DEAD / CANCELED）
     * @param retentionHours 保留小时数（成功 24、失败 168）
     * @param limit          本批行数
     * @return 待清理的 id（可能为空）
     */
    public List<Long> pickBatch(String status, int retentionHours, int limit) {
        return jdbc.queryForList(PICK_SQL, Long.class, status, retentionHours, limit);
    }

    /**
     * @return 真正被清的行数；小于 {@code ids.size()} 说明部分行已被并发清理（正常，不是错误）
     */
    public int clearPayload(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(CLEAR_PAYLOAD_TAIL.formatted(placeholders(ids)), ids.toArray());
    }

    /**
     * @return 被清的明细行数
     */
    public int clearSnippets(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbc.update(CLEAR_SNIPPET_TAIL.formatted(placeholders(ids)), ids.toArray());
    }

    private static String placeholders(List<Long> ids) {
        return String.join(",", Collections.nCopies(ids.size(), "?"));
    }
}
