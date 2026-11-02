/**
 * 归档与 PII 最小留存（T3.3，设计文档 §4.10）。
 *
 * <p>只清"密文列"（{@code body_enc} / {@code headers_enc} / {@code response_snippet}），
 * 不删行、不删明细：行是"这条为什么没到"的唯一证据，也是人工重放的前提（T3.2）。
 *
 * <p>两条保留期对应两种证据需求：成功 24h（够当天排障）、失败 7d（撑到人工重放）。
 * 分批 + 每批自动提交，是因为这张表同时是投递队列，长事务会挡住认领。
 */
package com.rc.notify.archive;
