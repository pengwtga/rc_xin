/**
 * 查询侧（T3.1）：把"这条通知现在怎么样、为什么没到"变成两个只读接口。
 *
 * <p>本包的三条规矩：
 * <ol>
 *   <li><b>只读</b>——不写任何状态的接口放在这里，避免"查一下顺手改个状态"这种演进；</li>
 *   <li><b>作用域由凭证决定</b>——caller 来自 API Key，SQL 一律带 {@code caller_id}；</li>
 *   <li><b>出参即脱敏后的最终形态</b>——body 不出现，header 只出白名单值（见 {@code HeaderMasker}）。</li>
 * </ol>
 *
 * <p>响应模型（{@code dto} 子包）与存储行模型（{@code store.*Row}）刻意不共用：
 * 前者是对外契约，改字段要考虑兼容；后者跟随表结构。两者之间的转换集中在
 * {@code NotificationQueryService}，正是脱敏与截断发生的地方。
 */
package com.rc.notify.query;
