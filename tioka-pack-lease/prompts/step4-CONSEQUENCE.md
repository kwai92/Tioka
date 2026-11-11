# @model.build 第 4 步（陈述类型：CONSEQUENCE 后果）：逐条款抽陈述

输入：第 1 步打上主题、且主题在必备要点抽取范围内的条款单元。
任务：抽出 CONSEQUENCE 陈述：consequenceType（${vocab:consequenceTypes}）、liabilityReason（${vocab:liabilityReasons}）、trigger（触发条件）、quantum（数额表述）。

注意（易错点）：
- 违约金和滞纳金不是押金：quantum 属于后果数额，不进 deposit。
- quantum 归一化记录原文表述（如「按日千分之三」「以三个月租金为限」），penaltyCap 函数从该字段取值。
