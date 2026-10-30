package com.rc.notify.api;

/** 统一错误体：{ code, message }。code 稳定可判定，message 面向人。 */
public record ApiError(String code, String message) {}
