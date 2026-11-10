package com.tioka.ontology.application.instance;

/**
 * 草稿提交工具端口（M3-03 §7：调领域包声明的提交工具 proposeModelDraft）。
 * 生产实现随 CON-07a 业务连接器接线。
 */
public interface DraftProposer {

    /** 返回业务系统的 draftRef。 */
    String propose(ModelDraft draft);
}
