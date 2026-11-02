package com.rc.notify.query.dto;

/**
 * 脱敏后的一个请求头（T3.1）。
 *
 * <p>只带名字与"可回显的值"，绝不带原始凭证：调用方关心的是"我当时到底带没带 Authorization"，
 * 不关心它的内容——而内容一旦出了存储层，加密就白做了。
 */
public record MaskedHeader(
        /** Header 名，保持调用方提交时的原始大小写（HTTP 头名不敏感，但排查时习惯看原样）。 */
        String name,
        /** 展示值：白名单内的头回显原文（截断），其余一律 {@code ****}。 */
        String value
) {}
