# @model.build 第 2 步：对象清点（对象类型 × 身份键）

输入：第 1 步打过标的条款单元。
任务：按对象类型清点对象，只输出身份键，不抽属性。输出受 lease.contract.inventory@1 Schema 约束。

对象类型与身份键（来自模型定义 lease.contract.yaml）：
- subject：code
- party：name + role
- feeItem：feeCode + subject（实例键形如 RENT@S1）
- deposit：subject + depositType + originalName
- crossRule：ruleType + targets

注意：
- 清点阶段不判断金额与期间，避免各步各抽各的押金、租金。
- 同一 feeCode 不同标的算不同对象（RENT@S1 与 RENT@S2）。
- 押金按 depositType 细分，不合并为「押金」；违约金和滞纳金不是押金，不进入 deposit 清点。
