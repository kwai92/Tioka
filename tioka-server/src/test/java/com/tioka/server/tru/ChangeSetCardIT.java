package com.tioka.server.tru;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * TRU-06b（M2-00 第 81 行）变更单卡片后端——❌ 缺口骨架，暂不执行。
 *
 * <p>缺口（2026-11-10，main = adc51c9）：TRU-05 变更单域模型与 prepare/commit 已齐备
 * （{@code ChangeSet} / {@code ChangeSetFactory} / {@code PreparedCallStore} /
 * {@code DefaultToolGateway#prepare:132} / {@code #commit:200}），但「变更单卡片」后端
 * 渲染与详情查询未实现。证据：
 * <ul>
 *   <li>{@code connector-api ImMessage} 无 ChangeTable 组件（M2-05 第 97 行要求）；</li>
 *   <li>M2-06 PK-25 指定的 {@code connector-biz/.../FeishuCardBuilder} 变更单分支不存在
 *       （全仓 grep FeishuCardBuilder 无命中）；</li>
 *   <li>{@code CardApprovalService#openOrReuse}（58–66 行）写入 card_json 仅含
 *       kind/key/title/summary/form/onTimeout，无 changeSet 字段——审批挂起的
 *       card_json 未按 M2-05 第 43 行带 {@code {changeSet, reversibility, estimated}}；</li>
 *   <li>审批台详情（HCI-05b）无变更单查询出口。</li>
 * </ul>
 *
 * <p>本文件按既有 IT 惯例（{@code OrchTimerIT}：@SpringBootTest @Tag("it")）放置；
 * 类级 @Disabled，未启动上下文，不影响默认构建。实现后移除 @Disabled 补齐断言，
 * 验收口径（M2-05 第 99–105、111 行）：
 *
 * <ol>
 *   <li>四种状态快照：PROPOSED、APPLIED、STALE、estimated 各渲染一张卡片；</li>
 *   <li>飞书卡片：标题 = changeSet.summary；标签按可逆性（不可撤销红色、estimated 加「推断」）；
 *       表格「对象 | 字段 | 原值 | 新值」前 10 条（UPDATE 只列变化字段）；合计记录数/金额；
 *       &gt;10 条或 estimated 时带「在 Tioka 中查看全部」按钮；</li>
 *   <li>审批台详情：有变更单时以变更单替代参数 JSON。</li>
 * </ol>
 */
@SpringBootTest
@Tag("it")
@Disabled("缺口：变更单卡片后端未实现——ImMessage 无 ChangeTable、无 FeishuCardBuilder 变更单分支、card_json 无 changeSet（PK-25/TRU-06b）；实现后移除 @Disabled 补齐断言")
class ChangeSetCardIT {

    @Test
    void proposedStateSnapshot() {
        // 占位：PROPOSED 状态卡片渲染快照
    }

    @Test
    void appliedStateSnapshot() {
        // 占位：APPLIED 状态卡片渲染快照
    }

    @Test
    void staleStateSnapshot() {
        // 占位：STALE 状态卡片渲染快照
    }

    @Test
    void estimatedStateSnapshot() {
        // 占位：estimated（推断）卡片渲染快照——标签与「查看全部」按钮
    }
}
