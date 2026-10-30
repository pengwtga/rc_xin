package com.rc.notify.store;

import java.util.Map;

/**
 * 请求内容编码边界（T1.1 文档 §2.4）。
 *
 * <p>抽接口的唯一目的：T2.4 换成 AES-256-GCM 实现时，Service / Repository / 投递器**零改动**。
 * <p>encode 的返回值直接落 `headers_enc` / `body_enc` 列；decode 在投递前还原（T1.2）。
 */
public interface PayloadCodec {

    /** Header 序列化（null 表示无 Header）。 */
    byte[] encodeHeaders(Map<String, String> headers);

    /** 请求体编码（null 表示无 body）。 */
    byte[] encodeBody(String body);

    /** 还原落库的 Header（入参可为 null，对应"无 Header"）。 */
    Map<String, String> decodeHeaders(byte[] stored);

    /** 还原落库的请求体（入参可为 null，返回 null 表示无 body）。 */
    String decodeBody(byte[] stored);
}
