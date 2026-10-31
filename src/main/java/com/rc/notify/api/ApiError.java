package com.rc.notify.api;

/** 统一错误体：{ code, message }。code 稳定可判定，message 面向人。 */
public record ApiError(
        /** 稳定错误码（VALIDATION_FAILED / UNAUTHORIZED / NOT_FOUND ...），供程序分支判定。 */
        String code,
        /** 面向人的说明，含具体字段信息，措辞可能调整，不适合程序解析。 */
        String message
) {}
