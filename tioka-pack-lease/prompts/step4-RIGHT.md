# @model.build 第 4 步（陈述类型：RIGHT 权利）：逐条款抽陈述

输入：第 1 步打上主题、且主题在必备要点抽取范围内的条款单元。
任务：抽出 RIGHT 陈述：rightType（${vocab:rightTypes}）、condition（行使条件）。

注意（易错点）：
- 「有权 / 可 / 得以」引导；与 OBLIGATION 区分。
- 解除权条款的触发条件（如逾期天数、次数）必须完整抽出，供 overdueDaysToTerminate / overdueTimesToTerminate 规则取值。
