package com.rc.notify.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * T1.1 占位实现：Header 序列化为 JSON、body 原样 UTF-8 编码，**明文入库**。
 *
 * <p>⚠️ 这是阶段性妥协：凭证类 Header 的真正保护在 T2.4（AES-256-GCM + 密钥由部署环境注入）。
 * 替换时只改本类，不动调用方。见 docs/T1.1-接入接口.md §7。
 */
@Component
public class PlaintextPayloadCodec implements PayloadCodec {

    private final ObjectMapper objectMapper;

    public PlaintextPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] encodeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(headers);
        } catch (JsonProcessingException e) {
            // Map<String,String> 不含不可序列化类型，这里属于程序错误
            throw new IllegalStateException("Header 序列化失败", e);
        }
    }

    @Override
    public byte[] encodeBody(String body) {
        return body == null ? null : body.getBytes(StandardCharsets.UTF_8);
    }
}
