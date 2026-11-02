package com.rc.notify.replay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工重放请求体（T3.2，设计文档 §4.6："带操作人与原因"）。
 *
 * <p>{@code reason} 必填是刻意的：重放是"人决定再花一次预算"的高风险动作，
 * 强制写原因让这个动作慢半拍——足够拦住"看到 DEAD 就全选重放"的条件反射。
 * {@code operator} 可缺省为调用方标识（脚本/系统触发时没有自然人）。
 */
public record ReplayRequest(
        /** 执行重放的操作人（自然人姓名/工号）；缺省用调用方标识，上限 64 字符。 */
        @Size(max = 64, message = "operator 不能超过 64 字符")
        String operator,

        /** 为什么要重放：通常是"凭证已更换 / 供应商已恢复 / 该失败是误报"。上限 512 字符。 */
        @NotBlank(message = "reason 不能为空——重放必须留下原因")
        @Size(max = 512, message = "reason 不能超过 512 字符")
        String reason
) {}
