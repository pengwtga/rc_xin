package com.rc.notify.store;

import java.util.Map;

/**
 * 请求内容编码边界（T1.1 文档 §2.4）。
 *
 * <p>抽接口的唯一目的：T2.4 换成 AES-256-GCM 实现时，Service / Repository / 查询接口**零改动**。
 * <p>返回值直接落 `headers_enc` / `body_enc` 列。
 */
public interface PayloadCodec {

    /** Header 序列化（null 表示无 Header）。 */
    byte[] encodeHeaders(Map<String, String> headers);

    /** 请求体编码（null 表示无 body）。 */
    byte[] encodeBody(String body);
}
