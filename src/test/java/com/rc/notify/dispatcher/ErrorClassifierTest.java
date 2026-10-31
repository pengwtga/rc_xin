package com.rc.notify.dispatcher;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类真值表全量验证（T2.1 文档 §3）——分类是纯函数，测试即文档，不需要任何 mock。
 *
 * <p>注意 {@code classify} 只有一个入参：分类<b>不看目标地址、不看 caller</b>。
 * 曾经有过按 host 的端点覆盖表（"某家供应商用 400 表示系统忙"），已在 review 中移除——
 * 那是在替下游的接口不规范买单（T2.1 文档 §2.3）。签名里的单参数就是这条决定的形状：
 * 想加回按地址的例外，先得改签名，改不动就说明不该加。
 */
class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    private static HttpDeliverer.Outcome http(int code) {
        return new HttpDeliverer.Outcome(code, null, 10, "body", null);
    }

    private static HttpDeliverer.Outcome network(String errorClass) {
        return new HttpDeliverer.Outcome(null, errorClass, 10, null, null);
    }

    @Test
    void 二百系全部成功() {
        for (int code : new int[]{200, 201, 204, 299}) {
            assertThat(classifier.classify(http(code)).type())
                    .as("statusCode=%d", code).isEqualTo(ErrorClassifier.Type.SUCCESS);
        }
    }

    @Test
    void 列出的四百系一次即死() {
        for (int code : new int[]{400, 401, 403, 404, 422}) {
            assertThat(classifier.classify(http(code)).type())
                    .as("statusCode=%d", code).isEqualTo(ErrorClassifier.Type.PERMANENT);
        }
    }

    @Test
    void 未列出的四百系默认永久_保守侧不烧预算() {
        // 410/413/415 都是请求侧问题：错判 PERMANENT 可人工重放（可逆），错判 RETRYABLE 烧光预算（不可逆）
        for (int code : new int[]{405, 409, 410, 413, 415, 418, 499}) {
            assertThat(classifier.classify(http(code)).type())
                    .as("statusCode=%d", code).isEqualTo(ErrorClassifier.Type.PERMANENT);
        }
    }

    @Test
    void 明确稍后再来的状态码可重试() {
        for (int code : new int[]{408, 429, 503}) {
            assertThat(classifier.classify(http(code)).type())
                    .as("statusCode=%d", code).isEqualTo(ErrorClassifier.Type.RETRYABLE);
        }
    }

    @Test
    void 五百系可重试() {
        for (int code : new int[]{500, 501, 502, 504}) {
            assertThat(classifier.classify(http(code)).type())
                    .as("statusCode=%d", code).isEqualTo(ErrorClassifier.Type.RETRYABLE);
        }
    }

    @Test
    void 网络层失败全部可重试() {
        for (String ec : new String[]{"TIMEOUT", "DNS", "TLS", "CONN_REFUSED", "IO"}) {
            assertThat(classifier.classify(network(ec)).type())
                    .as("errorClass=%s", ec).isEqualTo(ErrorClassifier.Type.RETRYABLE);
        }
    }

    @Test
    void 三百系按可重试兜底_跟随与否是SSRF校验的职责() {
        assertThat(classifier.classify(http(302)).type())
                .isEqualTo(ErrorClassifier.Type.RETRYABLE);
    }

    @Test
    void 四百二十九的RetryAfter透传_其余状态码不透传() {
        var with429 = new HttpDeliverer.Outcome(429, null, 10, null, 120L);
        assertThat(classifier.classify(with429).retryAfterSeconds()).isEqualTo(120L);

        // PERMANENT 分支：即使带了 Retry-After 也不透传（分类已定死，承诺无意义）
        var with400 = new HttpDeliverer.Outcome(400, null, 10, null, 120L);
        assertThat(classifier.classify(with400).retryAfterSeconds()).isNull();

        // 500：无 Retry-After 场景
        assertThat(classifier.classify(http(500)).retryAfterSeconds()).isNull();
    }
}
