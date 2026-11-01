package com.rc.notify.dispatcher;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 死信日志的脱敏规则（T2.3）。
 *
 * <p>为什么要单独测这个纯函数：{@code target_url} 里经常带 {@code ?access_token=xxx}，
 * 日志是全公司可读的存储。T2.4 加密的是数据库里的 headers_enc，管不到日志；
 * 这里漏一次，等于把下游凭证永久写进了日志系统。
 */
class LoggingDeadLetterSinkTest {

    @Test
    void 查询串整体抹掉_凭证与业务单号都不进日志() {
        assertThat(LoggingDeadLetterSink.safeUrl(
                "https://api.supplier.com/cb/order?access_token=SECRET&order_id=123"))
                .isEqualTo("https://api.supplier.com/cb/order?***");
    }

    @Test
    void 无查询串时原样保留路径() {
        assertThat(LoggingDeadLetterSink.safeUrl("http://10.0.0.8:8080/hooks/paid"))
                .isEqualTo("http://10.0.0.8:8080/hooks/paid");
    }

    @Test
    void userinfo被丢弃() {
        // 凭证也可能写在 authority 里（http://user:pass@host/...），只取 scheme/host/port/path 天然丢弃
        assertThat(LoggingDeadLetterSink.safeUrl("https://user:pass@api.supplier.com/cb"))
                .isEqualTo("https://api.supplier.com/cb");
    }

    @Test
    void 无路径时补斜杠_保证输出可直接点开() {
        assertThat(LoggingDeadLetterSink.safeUrl("https://api.supplier.com")).isEqualTo("https://api.supplier.com/");
    }

    @Test
    void 解析失败不透出原文() {
        // 不能"解析失败就回退到原串"——那样脱敏就成了摆设，IllegalArgumentException 的路径照样泄凭证
        assertThat(LoggingDeadLetterSink.safeUrl("http://exa mple.com/cb?token=SECRET")).isEqualTo("<unparsable>");
        assertThat(LoggingDeadLetterSink.safeUrl("not-a-url?token=SECRET")).isEqualTo("<unparsable>");
        assertThat(LoggingDeadLetterSink.safeUrl(null)).isEqualTo("-");
    }
}
