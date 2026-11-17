# M4 OBS-12 证据包离线校验（平台公钥与步骤）

> 状态：2026-11-14（M4 OBS-12 落地批次）。
> 事实来源：`EvidencePackService` / `EvidencePackVerifier`（system-biz `application/evidence`）、
> `EvidencePackSignerImpl`（system-biz `infrastructure/spi`）、`EvidencePackController`（system-biz `api`）、
> `V261__m4_obs12_evidence_pack.sql`、ADR-075 §5、M4-06 §7。

## 1. 口径

- 导出范围：一个 `subject`（`subjectType:subjectId`）或一个 `request_id`。
- 包内容（七段，各段一个文件）：`timeline`、`audit`、`ext-interaction`、`run-manifest`、
  `approvals`、`config-versions`、`audit-seal`（对应空间的审计哈希链封印记录与校验结果）。
- 打包：每个文件的 SHA-256 → 包整体哈希 → 平台密钥 `SHA256withRSA` 签名（十六进制）。
- 权限：`audit:export`（+ `audit:read`）；CONFIDENTIAL 内容按导出人权限决定是否包含，
  被省略时在 `manifest.omittedSections` 注明。轨迹正文另受 `trajectory:read:content` 控制。
- 审计：每次导出写审计动作 `timeline.evidence.export`（分类 DATA，资源类型 `evidence_pack`），
  记录条件、行数、文件数与包整体哈希。

## 2. 取得证据包与公钥

```bash
# 证据包（附件下载；响应头 X-Pack-Id / X-Pack-Sha256 回包标识与包整体哈希）
curl -sS -H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: $WS" \
  "https://<host>/api/system/evidence-pack/export?scopeType=SUBJECT&scopeRef=order:42" \
  -o pack.json

# 平台公钥（算法 / 密钥标识 / X.509 PEM）；需 audit:read
curl -sS -H "Authorization: Bearer $TOKEN" -H "X-Workspace-Id: $WS" \
  "https://<host>/api/system/evidence-pack/public-key" | jq -r '.data.publicKeyPem' > pub.pem
```

`scopeType` 取值：`REQUEST`（`scopeRef`=request_id）或 `SUBJECT`（`scopeRef`=`subjectType:subjectId`）。
可用重复的 `configObject=objectType:objectId` 纳入「相关配置版本」。

> 包同一性：撤销 X-Pack-Sha256 头仅作传输校验；最终以包内 `packageHash` + 平台签名 为准。

## 3. 包结构与哈希口径（formatVersion = 1）

```json
{
  "formatVersion": 1,
  "manifest": {
    "packId": "evp_...", "scopeType": "SUBJECT", "scopeRef": "order:42",
    "workspaceId": "ws_...", "exportedBy": "123", "exportedAt": "2026-11-14T03:00:00Z",
    "clearance": "SECRET", "includeConfidential": true,
    "signatureAlg": "SHA256withRSA", "keyId": "ab12cd34ef56a789",
    "fileCount": 7, "rowCount": 128, "omittedSections": []
  },
  "files": [
    { "name": "timeline", "sha256": "<hex>", "length": 1234, "content": "<该段 JSON 原文>" }
  ],
  "packageHash": "<hex>",
  "signature": "<hex>",
  "keyId": "<hex>"
}
```

- 文件哈希：`sha256(UTF-8(file.content))`，小写十六进制。
- **包整体哈希**：把每个文件按 `name` 升序拼成每行 `name + ':' + sha256 + '\n'`（含尾换行），
  对整串 UTF-8 字节取 `sha256`，小写十六进制。
- **签名**：对「`packageHash` 十六进制字符串」的 UTF-8 字节，用平台私钥做 `SHA256withRSA`，
  输出小写十六进制。
- `keyId` = 平台公钥 DER 的 SHA-256 前 16 位十六进制（用于密钥轮换追溯）。

## 4. 离线校验（推荐：用平台自带校验器）

`EvidencePackVerifier.verify(byte[] bundle, String publicKeyPem, ObjectMapper mapper)` 返回
`VerifyReport(ok, packageHashMatch, signatureValid, declaredPackageHash, recomputedPackageHash, files)`：
逐文件比对内容摘要 → 重算包整体哈希 → 以公钥验签；任一步不符即 `ok=false`。

```java
byte[] bundle = Files.readAllBytes(Path.of("pack.json"));
String pub = Files.readString(Path.of("pub.pem"));
EvidencePackVerifier.VerifyReport r = EvidencePackVerifier.verify(bundle, pub, objectMapper);
if (!r.ok()) { throw new IllegalStateException("证据包校验失败: " + r); }
```

## 5. 离线校验（不依赖平台代码：openssl 复算）

```bash
# 1) 逐文件重算并核对；jq -r 取原始 content，printf 不加额外换行
jq -r '.files[] | "\(.name)\t\(.sha256)"' pack.json | while IFS=$'\t' read -r name declared; do
  actual=$(jq -r --arg n "$name" '.files[] | select(.name==$n) | .content' pack.json | sha256sum | cut -d' ' -f1)
  [ "$actual" = "$declared" ] && echo "OK  $name" || echo "BAD $name"
done

# 2) 重算包整体哈希（按 name 升序，每行 name:sha256）
jq -r '.files | sort_by(.name)[] | "\(.name):\(.sha256)"' pack.json > canon.txt
printf '\n' >> canon.txt                       # 末行补尾换行
recomputed=$(sha256sum canon.txt | cut -d' ' -f1)

# 3) 验签：packageHash 串 的 UTF-8 字节 → sig 十六进制转二进制
jq -r '.packageHash' pack.json > packageHash.txt
jq -r '.signature'   pack.json | xxd -r -p > sig.bin
openssl dgst -sha256 -verify pub.pem -signature sig.bin packageHash.txt
# 期望输出：Verified OK
```

判据：三步全 OK 且 `recomputed` == 包内 `packageHash`，方可认定包未被篡改。

## 6. 密钥存放与轮换

- 私钥 at-rest 存 `SecretStore`（命名空间 `evidence-pack`，键 `signing-key-private-pem` /
  `signing-key-public-pem`）；首次导出时生成 RSA-2048 并持久化，之后复用。
- 读取私钥的实现位于白名单包 `com.tioka.system.infrastructure.spi`（ADR-014 D5）。
- 轮换：清空 `evidence-pack` 命名空间的两把密钥并重启，服务将生成新的密钥对；
  此时 `keyId` 变化——**旧包须用导出时对应的公钥校验**，故导出时请随包留存 `keyId` 与对应公钥。

## 7. 运维检查点

1. `/api/system/evidence-pack/public-key` 可访问且 `algorithm=SHA256withRSA`。
2. 抽样导出：`EvidencePackVerifier.verify(...)` 的 `ok=true`。
3. 审计可查回：`action=timeline.evidence.export`，`resourceType=evidence_pack`，
   `details.packageHash` 与响应头 `X-Pack-Sha256` 一致。
4. 无 `audit:export` 的身份调用导出 → 403（`RequirePermission`）。
