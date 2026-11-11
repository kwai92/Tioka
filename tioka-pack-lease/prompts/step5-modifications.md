# @model.build 第 5 步：修改声明（只对补充协议、变更协议执行）

输入：补充 / 变更 / 续约 / 终止 / 转让合同的条款单元。
任务：抽出修改声明：修改类型（模型定义 modifications 词表）、新内容、生效期、去向。

修改类型 → 变更操作（词表占位 ${mod:typeToOps}，不手抄）：
- 续约 → @TERM_RENEW；调价 / 免租调整 → @ITEM_ADJUST；新增费用 → @ITEM_ADD；
- 主体变更 → @PARTY_CHANGE；转让 / 回购 → @PARTY_TRANSFER；终止 → @TERM_TERMINATE；
- 日期调整 → @DATE_ADJUST；迁址 → @SUBJECT_ADD + @ARCHIVE_REBUILD。

注意（易错点）：
- 每份声明必须带生效期；生效日没有依据的标未知（V15），依赖它的操作暂不应用。
- 「新增还是调整」：同标的已有同类费用时是调整（V11），不是新增。
- 续约 / 调价必须带完整金额（V12）。
- 声明本身也是对象属性的证据来源，引用格式与第 3 步一致。
