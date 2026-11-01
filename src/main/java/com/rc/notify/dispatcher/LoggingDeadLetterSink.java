package com.rc.notify.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * 死信出口的默认实现：一行结构化 WARN（T2.3）。
 *
 * <p>三个刻意的选择：
 * <ul>
 *   <li><b>独立 logger 名 {@code notify.deadletter}</b>：M3 接告警时按 logger 名路由即可
 *       （日志系统对 "level=WARN 且 logger=notify.deadletter" 配一条规则），不用正则扫全量日志、
 *       也不会被正常重试的 WARN 淹没；</li>
 *   <li><b>单行 key=value</b>：一条死信一行，grep 与日志采集的解析成本最低；跨行打调
 *       在多线程日志里会与别的任务交错，反而不可读；</li>
 *   <li><b>URL 脱敏</b>：{@code target_url} 常带 {@code ?access_token=xxx} 之类的下游凭证，
 *       日志是全公司可读的存储，比数据库更不该出现明文（T2.4 加密的是 DB 侧，管不到日志）。</li>
 * </ul>
 *
 * <p>异常处理：本类不抛异常（只是 log.warn），Dispatcher 侧仍会统一 try/catch，
 * 这两层是给未来那些真的可能抛的实现（webhook 出口）留的余量。
 */
@Component
public class LoggingDeadLetterSink implements DeadLetterSink {

    /** 死信专用 logger：告警规则绑定这个名字，不与其他投递日志混。 */
    private static final Logger DEAD_LETTER_LOG = LoggerFactory.getLogger("notify.deadletter");

    @Override
    public void onDead(DeadLetter letter) {
        DEAD_LETTER_LOG.warn(
                "dead_letter id={} caller={} reason={} attempts={}/{} last_status={} error_class={} method={} target={} next_action={}",
                letter.notificationId(),
                letter.callerId(),
                letter.reason(),
                letter.attemptCount(),
                letter.maxAttempts(),
                letter.lastStatusCode() == null ? "-" : letter.lastStatusCode(),
                letter.errorClass() == null ? "-" : letter.errorClass(),
                letter.httpMethod(),
                safeUrl(letter.targetUrl()),
                nextAction(letter.reason()));
    }

    /**
     * 给值班的人一句可执行的下一步。
     *
     * <p>死信是"必须有人处理"的终态，日志里只写"它死了"等于把判断成本推给看日志的人：
     * 两种死法的处置完全不同——PERMANENT 是配置/凭证问题（改完直接重放），
     * BUDGET_EXHAUSTED 是下游一直没好（先确认对方恢复再重放，否则重放一次又死一次）。
     */
    private static String nextAction(DeadLetter.Reason reason) {
        return switch (reason) {
            case PERMANENT -> "fix_request_or_credential_then_replay";
            case BUDGET_EXHAUSTED -> "verify_downstream_then_replay";
        };
    }

    /**
     * 去掉 URL 的 query 与 userinfo，只保留 {@code scheme://host[:port]/path}。
     *
     * <p>path 保留：同一个供应商的不同路径是排查时区分"哪类回调"的关键信息；
     * query 一律抹掉：凭证与业务单号最常出现在 query 里，而排查并不需要它。
     * 解析失败时返回 {@code <unparsable>}，绝不把原始串透出去。
     */
    static String safeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        try {
            URI uri = new URI(raw);
            if (uri.getHost() == null) {
                return "<unparsable>";
            }
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return uri.getScheme() + "://" + uri.getHost() + port + path + (uri.getRawQuery() == null ? "" : "?***");
        } catch (URISyntaxException e) {
            return "<unparsable>";
        }
    }
}
