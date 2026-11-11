# @model.build 第 3 步（对象类型：deposit 押金）：逐对象抽属性

输入：第 2 步清点出的一个押金对象（身份：subject + depositType + originalName）与其相关条款单元。
输出：amount（金额）、refundTerm（退还条件）等取值 + 来源。

注意（易错点）：
- 违约金和滞纳金不是押金：违约金、滞纳金条款属于 CONSEQUENCE 陈述，不抽进 deposit。
- 押金类型按 ${vocab:depositTypes} 词表细分，originalName 保留合同原文叫法。
- 空白不是 0：金额留白标空白。
- 金额必须带来源引文（V05）；续约押金不得照抄旧合同金额（V13 押金不抄旧额）。
