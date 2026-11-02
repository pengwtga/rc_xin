package com.rc.notify.query.dto;

import java.util.List;

/**
 * 列表分页结果（T3.1）：键集分页（keyset），不是 OFFSET 分页。
 *
 * <p>{@code nextCursor} 为 null 表示<b>没有更多</b>——不额外给 {@code has_more} 布尔：
 * 两个表达同一件事的字段一旦实现有偏差，客户端就会在两个都错的时候看到互相矛盾的结果。
 */
public record NotificationPage(
        /** 本页数据，按单号倒序（最新的在前）。 */
        List<NotificationSummary> items,
        /** 下一页游标；null 表示已到末尾。语义是"上一页最后一条的单号"。 */
        Long nextCursor
) {}
