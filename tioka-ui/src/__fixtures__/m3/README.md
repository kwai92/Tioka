# M3 前端 fixtures 约定（P3-00c）

后续 FE 包把页面级假数据放在本目录，按页面分子目录：

```
__fixtures__/m3/
  README.md          ← 本约定
  prompts/list.json
  timeline/sample.json
  eval/queue.json
  …
```

规则：

1. 只放 JSON（或纯文本样本）；不放密钥与真实租户数据。
2. 组件测试优先读本目录，再接真接口。
3. 命名与路由域对齐：`studio-*` / `console-*` / `admin-*`。
