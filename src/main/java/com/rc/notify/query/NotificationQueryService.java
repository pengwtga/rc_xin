package com.rc.notify.query;

import com.rc.notify.api.ApiException;
import com.rc.notify.query.dto.AttemptView;
import com.rc.notify.query.dto.MaskedHeader;
import com.rc.notify.query.dto.NotificationDetailResponse;
import com.rc.notify.query.dto.NotificationPage;
import com.rc.notify.query.dto.NotificationSummary;
import com.rc.notify.query.dto.ReplayView;
import com.rc.notify.store.NotificationReadRepository;
import com.rc.notify.store.PayloadCodec;
import com.rc.notify.store.ReplayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 查询用例（T3.1）：鉴权作用域 → 读取 → 脱敏 → 组装。
 *
 * <p>这一层的职责边界很清楚：<b>它决定"什么能出去"</b>。Repository 只管把行读出来，
 * Controller 只管 HTTP 形状，中间的脱敏与截断必须只有一处实现，否则漏一个出口就前功尽弃。
 */
@Service
public class NotificationQueryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueryService.class);

    /** 合法状态集合（与 notification.status 注释一致）。 */
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "RETRY_WAIT", "PROCESSING", "SUCCEEDED", "DEAD", "CANCELED");

    /** 默认页大小：一屏能看完，又足够覆盖一次巡检的量。 */
    static final int DEFAULT_LIMIT = 20;
    /** 页大小上限：100。再大就不是"扫一眼"而是导出，导出该走离线通道。 */
    static final int MAX_LIMIT = 100;
    /** 单次返回的明细条数上限；超出时保留最近的 N 条（见 Repository 注释）。 */
    static final int MAX_ATTEMPT_ROWS = 200;
    /** 响应片段展示上限（字符）：落库已是 2048，这里再截到 512 —— 见 T3.1 文档 §2.6。 */
    static final int SNIPPET_DISPLAY_MAX = 512;

    private final NotificationReadRepository readRepo;
    private final PayloadCodec codec;
    private final HeaderMasker masker;
    private final ReplayRepository replayRepo;

    public NotificationQueryService(NotificationReadRepository readRepo, PayloadCodec codec,
                                    HeaderMasker masker, ReplayRepository replayRepo) {
        this.readRepo = readRepo;
        this.codec = codec;
        this.masker = masker;
        this.replayRepo = replayRepo;
    }

    /**
     * 查单条详情（设计文档 §5：状态 + 每轮 attempt 明细）。
     *
     * <p>调用方的单号与他人单号、不存在的单号给出<b>同一个 404</b>：见 {@code ApiException#notFound}。
     *
     * @throws ApiException 404 —— 不存在或不属于当前调用方
     */
    public NotificationDetailResponse detail(long id, String callerId) {
        NotificationReadRepository.DetailRow row = readRepo.find(id, callerId)
                .orElseThrow(() -> ApiException.notFound("通知不存在或无权访问"));

        List<NotificationReadRepository.AttemptRow> attempts =
                readRepo.findAttempts(id, MAX_ATTEMPT_ROWS);

        HeaderView headers = decodeHeaders(id, row.headersEnc());

        List<ReplayView> replays = replayRepo.findReplays(id).stream()
                .map(r -> new ReplayView(r.operator(), r.reason(),
                        r.attemptCountBefore(), r.maxAttemptsAfter(), r.createdAt()))
                .toList();

        return new NotificationDetailResponse(
                row.id(), row.status(), row.idempotencyKey(), row.targetUrl(), row.httpMethod(),
                row.attemptCount(), row.maxAttempts(), row.nextAttemptAt(),
                row.lastStatusCode(), row.lastErrorClass(), row.createdAt(), row.updatedAt(),
                row.hasBody(), headers.masked(), headers.decodable(),
                attempts.stream().map(NotificationQueryService::toView).toList(),
                replays);
    }

    /**
     * 列表（设计文档 §5：`?status=DEAD&cursor=` 拉取式消费死信）。
     *
     * @param status 状态过滤，null/空表示不过滤
     * @param cursor 上一页最后一条的单号，null/空表示第一页
     * @param limit  页大小，null 用默认值
     */
    public NotificationPage list(String callerId, String status, String cursor, Integer limit) {
        String normalizedStatus = normalizeStatus(status);
        Long beforeId = parseCursor(cursor);
        int pageSize = normalizeLimit(limit);

        List<NotificationReadRepository.SummaryRow> rows =
                readRepo.page(callerId, normalizedStatus, beforeId, pageSize);

        // 多取的那一条只用来判断"还有没有下一页"，不返回给调用方
        boolean hasMore = rows.size() > pageSize;
        List<NotificationReadRepository.SummaryRow> page = hasMore ? rows.subList(0, pageSize) : rows;
        Long nextCursor = hasMore ? page.get(page.size() - 1).id() : null;

        return new NotificationPage(page.stream().map(NotificationQueryService::toSummary).toList(), nextCursor);
    }

    // ——— 参数归一化 ———

    /**
     * 状态过滤归一化：大小写不敏感（人写 `dead` 与 `DEAD` 是一个意思）。
     *
     * <p>不在枚举里回 422 而不是"当成不过滤"：静默忽略过滤条件会让调用方以为看到的是死信清单，
     * 实际是全量清单——排查时按错的集合做决策，比直接报错危险得多。
     */
    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String upper = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(upper)) {
            throw ApiException.validation("status 只支持 " + STATUSES.stream().sorted().toList() + "，当前: " + status);
        }
        return upper;
    }

    /**
     * 游标解析：游标就是"上一页最后一条的单号"，十进制字符串。
     *
     * <p>不做 base64 之类的不透明化：单号本来就对外暴露（提交响应里就有），
     * 编码只会让排障多一步解码，不带来任何安全收益。将来若要改排序键（例如按
     * {@code next_attempt_at} 翻页），再引入带版本前缀的游标格式，届时旧客户端会拿到 422 而不是错页。
     */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw ApiException.invalidParameter("cursor 必须是上一页返回的 next_cursor 值，当前: " + cursor);
        }
        if (value <= 0) {
            throw ApiException.validation("cursor 必须是正整数，当前: " + cursor);
        }
        return value;
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.validation("limit 取值范围 1.." + MAX_LIMIT + "，当前: " + limit);
        }
        return limit;
    }

    // ——— 脱敏 ———

    /**
     * 解密 + 脱敏。
     *
     * <p><b>解密失败不能让整个查询 500</b>：密钥轮换、历史密文损坏时，运维恰恰最需要看这条单
     * 的状态与明细。所以失败只降级为"headers 不可用"（{@code headersDecodable=false}），
     * 其余字段照常返回。日志里只记单号与异常类型，<b>不记异常堆栈里的任何密文</b>。
     */
    private HeaderView decodeHeaders(long id, byte[] headersEnc) {
        if (headersEnc == null || headersEnc.length == 0) {
            return new HeaderView(List.of(), true);   // 本来就没带头：不是"解不开"
        }
        try {
            Map<String, String> plain = codec.decodeHeaders(headersEnc);
            return new HeaderView(masker.mask(plain), true);
        } catch (RuntimeException e) {
            log.warn("查询时 header 解密失败，已降级为不返回 header：notification={}, reason={}",
                    id, e.getClass().getSimpleName());
            return new HeaderView(List.of(), false);
        }
    }

    // ——— 行 → DTO ———

    private static NotificationSummary toSummary(NotificationReadRepository.SummaryRow r) {
        return new NotificationSummary(r.id(), r.status(), r.idempotencyKey(), r.targetUrl(),
                r.httpMethod(), r.attemptCount(), r.maxAttempts(), r.nextAttemptAt(),
                r.lastStatusCode(), r.lastErrorClass(), r.createdAt(), r.updatedAt());
    }

    private static AttemptView toView(NotificationReadRepository.AttemptRow r) {
        return new AttemptView(r.attemptNo(), r.startedAt(), r.durationMs(), r.statusCode(),
                r.errorClass(), truncate(r.responseSnippet(), SNIPPET_DISPLAY_MAX));
    }

    /**
     * 按<b>码点</b>截断，不按 char：直接 {@code substring} 可能把一个 emoji / 生僻字的代理对切成两半，
     * 产出非法 UTF-8 字符（下游 JSON 解析器会报错，而错误信息完全指不到这里）。
     */
    private static String truncate(String s, int maxCodePoints) {
        if (s == null) {
            return null;
        }
        if (s.codePointCount(0, s.length()) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    /** 解密结果的两种状态一起传，避免"空数组"同时表示"没带头"与"解不开"。 */
    private record HeaderView(List<MaskedHeader> masked, boolean decodable) {}
}
