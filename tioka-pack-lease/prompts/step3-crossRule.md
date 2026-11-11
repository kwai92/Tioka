# @model.build 第 3 步（对象类型：crossRule 跨项规则）：逐对象抽属性

输入：第 2 步清点出的一个跨项规则对象（身份：ruleType + targets）与其相关条款单元。
输出：ruleType（${vocab:crossRuleTypes}：孰高 / 孰低 / 抵扣）、targets（参与的费用项键列表）+ 来源。

注意（易错点）：
- targets 必须引用已清点的费用项身份键（如 RENT@S1、MGMT@S1），不引用金额数值。
- 「就高」「就低」「抵扣」按词表归类，不自由发挥。
