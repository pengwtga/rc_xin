package com.rc.notify.dispatcher;

/**
 * 死信事件（T2.3）：一条通知进入终态 {@code DEAD} 时发出的事实描述。
 *
 * <p>只带"人判断能不能重放"所需的字段，不带 headers/body——那两样含下游凭证与业务数据，
 * 要让运维看全文应该走 T3.1 的查询接口（有鉴权、有脱敏），而不是让它随日志流向各处的日志系统。
 *
 * @param notificationId 通知单号，排查入口
 * @param callerId       调用方标识：不同 caller 的死信要分别告警，混在一起看不出是谁的供应商挂了
 * @param targetUrl      目标地址（原样；落日志前由 sink 自行脱敏 query）
 * @param httpMethod     HTTP 方法，重放前的语义判断要用（POST 重放可能造成重复业务动作）
 * @param attemptCount   已消耗的尝试次数
 * @param maxAttempts    该条的预算上限
 * @param lastStatusCode 最后一次 HTTP 状态码；网络层失败为 null
 * @param errorClass     最后一次失败类别（TIMEOUT/DNS/TLS/CONN_REFUSED/IO/HTTP_3XX/4XX/5XX）
 * @param reason         为什么会死：一次即死 vs 预算耗尽——两者的处置方式完全不同
 */
public record DeadLetter(
        long notificationId,
        String callerId,
        String targetUrl,
        String httpMethod,
        int attemptCount,
        int maxAttempts,
        Integer lastStatusCode,
        String errorClass,
        Reason reason
) {

    /**
     * 死信原因。
     *
     * <p>这个区分不是为了好看：{@link #PERMANENT} 说明"请求或凭证有问题"，改配置后人工重放即可；
     * {@link #BUDGET_EXHAUSTED} 说明"下游持续故障"，重放前得先确认下游恢复。
     * 混成一个 DEAD 状态码，值班的人第一件事只能去翻日志猜是哪种。
     */
    public enum Reason {
        /** 分类为 PERMANENT（3xx/4xx）：请求本身有问题，重试无意义，一次即死，不消耗预算。 */
        PERMANENT,
        /** RETRYABLE 但尝试次数已达 max_attempts：下游一直没好，预算是被正常花完的。 */
        BUDGET_EXHAUSTED
    }
}
