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
 * <p>三个刻意的设计边界（T2.1 文档 §2.1 / §2.2）：
 * <ul>
 *   <li>分类是<b>只看结果的纯函数</b>：入参只有投递结果，不看目标地址、不看调用方。
 *       "某家供应商用 400 表示系统忙"曾被实现为端点覆盖表（按 host 配置例外），
 *       已在 review 中移除——那是替下游的接口不规范买单，且假设了"同 host 语义一致"这个
 *       未经观测的前提（§2.3）；</li>
 *   <li>未列出的 4xx 默认 PERMANENT：错杀可人工重放（可逆），烧光预算不可逆；</li>
 *   <li>不读配置、无状态：类里没有任何分支依赖外部数据，真值表即可穷尽全部行为。</li>
 * </ul>
 */
@Component
public class ErrorClassifier {

    public enum Type {
        /** 2xx：投递成功，任务终结。 */
        SUCCESS,
        /** 请求本身有问题（4xx），重试结果一样：一次即 DEAD，不消耗重试预算。 */
        PERMANENT,
        /** 对方内部故障或网络问题（5xx / 超时 / DNS / TLS / 连接拒绝），可能自愈。 */
        RETRYABLE
    }

    /**
     * 分类结果。retryAfterSeconds 仅 RETRYABLE 且对端明确给出时非空（429/503 的 Retry-After），
     * 调用方应优先于退避公式采用。
     */
    public record Decision(
            /** 三级分类结果，决定下一跳状态（SUCCEEDED / DEAD / RETRY_WAIT）。 */
            Type type,
            /** 对端承诺的等待秒数；非 RETRYABLE 或未给出时为 null。 */
            Long retryAfterSeconds
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
     * @param outcome 投递结果（statusCode 与 errorClass 互斥）
     */
    public Decision classify(HttpDeliverer.Outcome outcome) {
        if (outcome.isSuccess()) {
            return new Decision(Type.SUCCESS, null);
        }

        // 网络层失败（无 HTTP 响应）：可能自愈，一律 RETRYABLE
        if (outcome.statusCode() == null) {
            return new Decision(Type.RETRYABLE, null);
        }

        int code = outcome.statusCode();
        if (RETRYABLE_HINT_CODES.containsKey(code)) {
            return new Decision(Type.RETRYABLE, outcome.retryAfterSeconds());
        }
        if (code >= 500) {
            return new Decision(Type.RETRYABLE, null);
        }
        if (code >= 400) {
            // 4xx：列出的明确 PERMANENT；未列出的保守侧同样 PERMANENT（§2.2）
            return new Decision(Type.PERMANENT, null);
        }
        // 3xx：投递器禁重定向（T1.2），跟随与否是 T2.4 SSRF 校验的职责；此处按重试兜底
        return new Decision(Type.RETRYABLE, null);
    }
}
