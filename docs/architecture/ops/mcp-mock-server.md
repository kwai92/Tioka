# MCP Mock Server（本地 tools/list + tools/call）

用于联调 `tioka-module-mcp` 的最小 mock，不依赖真实远端 MCP。

## 方式 A：npx `@modelcontextprotocol/server-everything`

```bash
# 终端 A：stdio MCP everything（列出若干演示 tools）
npx -y @modelcontextprotocol/server-everything
```

HTTP/SSE 桥（可选，需自行加一层 SSE→stdio proxy）。更简单：用项目 `tioka.mcp.mock=true`（见 `application-ext.yml` / `TIOKA_MCP_MOCK`）走内置 mock，无需进程外服务。

## 方式 B：最小 JSON-RPC Docker（HTTP）

```bash
docker run --rm -p 3921:3921 \
  -e MCP_NAME=mock \
  ghcr.io/modelcontextprotocol/servers:latest \
  # 若镜像不可用，可自备一个 echo JSON-RPC 容器：
```

自备 echo（示意）：容器内监听 `POST /mcp`，对：

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

返回：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "echo",
        "description": "echo args",
        "inputSchema": { "type": "object", "properties": { "text": { "type": "string" } } }
      }
    ]
  }
}
```

对 `tools/call`：

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}
```

返回 `result.content` 含文本即可。

## 接入 Tioka

1. 控制台创建 MCP Server：`baseUrl` 指向 mock（如 `http://127.0.0.1:3921`）。
2. 同步 tools → 建 Grant → Agent 草稿勾选 Grant → 发布。
3. Chat 发送触发 tool 的消息，观察 Runtime `tool_call` / `tool_result` Part。

内置开关：

```yaml
tioka:
  mcp:
    mock: true   # 或环境变量 TIOKA_MCP_MOCK=true
```
