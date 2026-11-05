# 飞书 IM 卡片续办（R3-05）

> 官方通道：`FeishuImChannelAdapter`。无 App 凭证时写入 outbox；有凭证时走 OpenAPI。

## 前置

1. 飞书开放平台创建企业自建应用，开通「获取与发送单聊、群组消息」等权限。
2. 配置（任选）：

```yaml
# application-ext.yml 或环境变量
tioka:
  connector:
    feishu:
      app-id: ${FEISHU_APP_ID:}
      app-secret: ${FEISHU_APP_SECRET:}
```

3. 启动 server（建议 `ext` + 外置 PG，本地也可先 outbox 验链路）。

## 续办主链（与审批台对齐）

```
IM 事件 / 演示 API
  → Orch ingestEvent（可经 orch_rule）
  → claim + openAttention（或 Rule action=open_attention）
  → 审批台 / Chat approval_card 提交
  → orch.answer → Run READY
  → Adapter 发文本/卡片回 IM（有凭证）或 outbox（无凭证）
```

## 沙箱验收清单

| 步骤 | 期望 |
| --- | --- |
| 无凭证 `POST` 发消息 | 日志 `[feishu] outbox ...`；表/仓储有记录 |
| 配置 App 后发文本 | 飞书会话可见消息 |
| Gate `g2_dualCardsImAndFork` | CI mock/outbox 路径绿 |
| 人工：approval → 回 IM | 需租户沙箱；记录截图/日志后可签 R3 Exit |

## 相关代码

- `tioka-module-connector-biz/.../FeishuImChannelAdapter.java`
- `OrchService.openAttention` / `answer`
- `CardPartService.submitApproval`
- UI：`Approval.vue`、Chat 内 `approval_card`
