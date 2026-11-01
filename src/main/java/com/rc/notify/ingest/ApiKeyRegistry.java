package com.rc.notify.ingest;

import com.rc.notify.config.AuthProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * api-key → caller 的索引（T1.1）。
 *
 * <p>构造期一次性建表并做冲突检查：两个 client 配同一个 key 属于部署错误，启动即失败（fail fast），
 * 而不是等线上出现"提交到了错误 caller"才发现。
 */
@Component
public class ApiKeyRegistry {

    private final Map<String, AuthProperties.Client> byApiKey;

    public ApiKeyRegistry(AuthProperties auth) {
        Map<String, AuthProperties.Client> map = new LinkedHashMap<>();
        for (AuthProperties.Client client : auth.clients()) {
            if (client.apiKey() == null || client.apiKey().isBlank()) {
                throw new IllegalStateException("[启动自检失败] notify.auth.clients 存在空的 api-key");
            }
            if (client.callerId() == null || client.callerId().isBlank()) {
                throw new IllegalStateException(
                        "[启动自检失败] api-key=%s 缺少 caller-id".formatted(client.apiKey()));
            }
            // 预算下界（T2.3）：max_attempts 是"总尝试次数"而非"重试次数"，0 表示一次都不投——
            // 那不该用配置表达，用配置表达的结果是任务静默进 DEAD、且没有任何一次 HTTP 发生过
            if (client.maxAttempts() != null && client.maxAttempts() < 1) {
                throw new IllegalStateException(
                        "[启动自检失败] caller-id=%s 的 max-attempts(%d) 必须 >= 1（它是最多尝试次数，含首次投递）"
                                .formatted(client.callerId(), client.maxAttempts()));
            }
            if (map.putIfAbsent(client.apiKey(), client) != null) {
                throw new IllegalStateException(
                        "[启动自检失败] api-key=%s 被多个 client 重复配置".formatted(client.apiKey()));
            }
        }
        this.byApiKey = Map.copyOf(map);
    }

    public Optional<AuthProperties.Client> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byApiKey.get(apiKey));
    }

    /** 已注册的接入方数量（启动日志用）。 */
    public int size() {
        return byApiKey.size();
    }
}
