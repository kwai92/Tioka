# @model.build 第 4 步（陈述类型：OBLIGATION 义务）：逐条款抽陈述

输入：第 1 步打上主题、且主题在必备要点抽取范围内的条款单元。
任务：抽出 OBLIGATION 陈述：action（${vocab:actions}）、object（对象）、period（期间）。

注意（易错点）：
- 只抽必备要点涉及的主题（definitions/topic-checkpoint-map.yaml）；19 个主题的覆盖仍全部输出。
- 「应 / 须 / 应当」引导的条款是义务；「有权 / 不得」是 RIGHT / 禁令，不要混入。
- 每条陈述带证据单元引用；证据绑定由平台校验（引文必须能在单元原文中找到）。
