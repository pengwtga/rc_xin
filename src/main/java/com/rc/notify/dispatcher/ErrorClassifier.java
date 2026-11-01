package com.rc.notify.dispatcher;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 失败分级（T2.1，设计文档 §4.4）——整套可靠投递里最重要的一个判断。
 *
 * <p>"失败就重试 3 次"是无分类重试：一个 400 被重试 10 次，占满 worker、拉起告警风暴，
 * 还让真正的 5xx 排不上队。本类把投递结果折叠为三级：
 * <ul>
 *   <li>{@link Type#SUCCESS} —— 2xx；</li>
 *   <li>{@link Type#PERMANENT} —— 请求本身有问题（重试结果一样），一次即 DEAD，不花预算；</li>
 *   <li>{@link Type#RETRYABLE} —— 对方内部故障或网络问题，可能自愈。</li>
 * </ul>
 *
 * <p>四个刻意的设计边界（T2.1 文档 §2.1 / §2.2，T2.3 文档 §2.3）：
 * <ul>
 *   <li>分类是<b>只看结果的纯函数</b>：入参只有投递结果，不看目标地址、不看调用方。
 *       "某家供应商用 400 表示系统忙"曾被实现为端点覆盖表（按 host 配置例外），
 *       已在 review 中移除——那是替下游的接口不规范买单，且假设了"同 host 语义一致"这个
 *       未经观测的前提（§2.3）；</li>
 *   <li>未列出的 4xx 默认 PERMANENT：错杀可人工重放（可逆），烧光预算不可逆；</li>
 *   <li>3xx 同样 PERMANENT（T2.3 修正）：投递器禁重定向，收到 3xx 意味着目标地址已变或
 *       对端把请求转去了登录页——重试 10 次也不会变，属"配置问题"而非"对方暂时故障"。
 *       T2.1 曾把它当归 RETRYABLE，等于用整份预算换同一条 302；</li>
 *   <li>不读配置、无状态：类里没有任何分支依赖外部数据，真值表即可穷尽全部行为。</li>
 * </ul>
 *
 * <p>本类同时是 {@code error_class} 的<b>唯一产生者</b>：落库的失败类别必须与晋级决策出自同一次
 * 判断，否则两者会漂移——T2.3 之前就存在这个漂移：5xx 被正确判成 RETRYABLE，error_class 却是
 * NULL（只从投递器取值，而它只在网络层有值），于是"按 error_class 告警"根本看不见 5xx。
 */
@Component
public class ErrorClassifier {

    /**
     * 有 HTTP 响应时的失败类别：HTTP_ + 状态码百位。
     *
     * <p>为什么不带具体状态码（{@code HTTP_500}）：status_code 已单独成列，
     * error_class 的职责是"告警路由用的粗分组"——按类别聚合才看得出"5xx 在涨"，
     * 再按 401/404 细分是查询接口该做的事。
     */
    public static final String HTTP_3XX = "HTTP_3XX";
    public static final String HTTP_4XX = "HTTP_4XX";
    public static final String HTTP_5XX = "HTTP_5XX";

    /**
     * 出站目标被安全策略拒绝（T2.4）。不是网络故障，也不是对端返回的状态码——
     * 请求一个字节都没发出去。放在这里是因为"该不该重试"的判定必须只有一个入口。
     */
    public static final String SSRF_BLOCKED = "SSRF_BLOCKED";

    public enum Type {
        /** 2xx：投递成功，任务终结。 */
        SUCCESS,
        /** 请求本身有问题（3xx/4xx），重试结果一样：一次即 DEAD，不消耗重试预算。 */
        PERMANENT,
        /** 对方内部故障或网络问题（5xx / 超时 / DNS / TLS / 连接拒绝），可能自愈。 */
        RETRYABLE
    }

    /**
     * 分类结果。
     */
    public record Decision(
            /** 三级分类结果，决定下一跳状态（SUCCEEDED / DEAD / RETRY_WAIT）。 */
            Type type,
            /** 对端承诺的等待秒数；非 RETRYABLE 或未给出时为 null。 */
            Long retryAfterSeconds,
            /** 失败类别：网络层取投递器的 TIMEOUT/DNS/TLS/CONN_REFUSED/IO，有响应时归为 HTTP_3XX/4XX/5XX；成功为 null。 */
            String errorClass
    ) {}

    /**
     * 明确要求"稍后再来"的状态码。
     *
     * <p>注意这里只有 RETRYABLE 白名单、没有 PERMANENT 白名单：4xx 整体按 PERMANENT 兜底
     * （§2.2），再列一遍 400/401/403/404/422 是个不参与运行逻辑的常量——之前那版就留着这样
     * 一个只出现在注释里的死集合，已删除。
     */
    private static final Map<Integer, Boolean> RETRYABLE_HINT_CODES = Map.of(408, true, 429, true, 503, true);

    /**
     * 纯函数分类。任何输入都有确定输出，不抛异常——分类器自身绝不成为投递线程的新故障面。
     *
     * @param outcome 投递结果（statusCode 与 errorClass 互斥：有响应则 statusCode 非空）
     */
    public Decision classify(HttpDeliverer.Outcome outcome) {
        if (outcome.isSuccess()) {
            // 成功不留 error_class：写个 "NONE" 只会让"按 error_class 分组"的查询多一行噪声
            return new Decision(Type.SUCCESS, null, null);
        }

        // 网络层失败（无 HTTP 响应）：可能自愈，一律 RETRYABLE，类别沿用投递器的折叠结果
        if (outcome.statusCode() == null) {
            if (SSRF_BLOCKED.equals(outcome.errorClass())) {
                // 策略拒绝不是"暂时故障"：目标地址被禁止，重试一万次结果一样，一次即死
                return new Decision(Type.PERMANENT, null, SSRF_BLOCKED);
            }
            return new Decision(Type.RETRYABLE, null, outcome.errorClass());
        }

        int code = outcome.statusCode();
        String errorClass = httpClass(code);
        if (RETRYABLE_HINT_CODES.containsKey(code)) {
            return new Decision(Type.RETRYABLE, outcome.retryAfterSeconds(), errorClass);
        }
        if (code >= 500) {
            return new Decision(Type.RETRYABLE, null, errorClass);
        }
        // 3xx/4xx：确定性结果，一次即 DEAD。T2.1 曾把 3xx 当归 RETRYABLE，已修正（类注释第 3 条）
        return new Decision(Type.PERMANENT, null, errorClass);
    }

    /** 状态码 → 粗分组。只处理非 2xx 的分支，调用方已先行判定成功。 */
    private static String httpClass(int code) {
        if (code >= 500) {
            return HTTP_5XX;
        }
        if (code >= 400) {
            return HTTP_4XX;
        }
        return HTTP_3XX;
    }
}
