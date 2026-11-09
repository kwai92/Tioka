#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 2 · 流量与网络链路连通视图（G5 部署设计 §3.2，v1.1 按主理人基线更正重绘）

场景识别：网络拓扑 / 关系图。工具选择：Python 标准库零依赖 SVG（与 G3 / G4 一致）。
组件清单：外部调用方 4 类 / 入口链路 / 内部互联链路 / 跨网络互联（出口）3 类。
连接关系：现状 compose 将 7 个中间件端口映射宿主机、compose 内无 8080 / 443
与应用容器、应用由本地进程启动；设计层目标态仅 443 经反向代理入站（G6 前不落地）。
内部按 JDBC / RESP / OIDC / MQ / OTLP，出口经宿主机 NAT 或代理到 E-xx；
无跨 VPC、无跨账号、无跨 Region、无云专线。公网可达性待 U 系列仓库外取证。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 900
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 部署设计 · 图 2 流量与网络链路连通视图",
           "入口 / 内部 / 跨网三表图示；私有化单机无云上 DMZ 分层；网段与 CIDR 划分不适用；现状 7 端口映射宿主机、无 8080 / 443 / 应用容器，设计层目标态仅 443，公网可达性待取证")

# ── 调用方带 ────────────────────────────────────────
c.band(82, 116, "调用方（宿主机外部）",
       "浏览器、飞书 IM 回调、运维终端、模型 Provider 回包；凭据经环境变量注入，不落代码；公网可达性取决于宿主机 iptables 与客户侧策略",
       "#eff6ff", "#2563eb", "4 类调用方")
callers = ["浏览器与客户端", "飞书开放平台与 IM", "运维与交付终端", "OpenAI 兼容 Provider"]
for i, t in enumerate(callers):
    c.node(60 + i * 330, 144, 314, 46, t, "HTTPS 或内网 HTTP", "#dbeafe", "#3b82f6", tsize=11.5, ssize=9)

# ── 入口链路带 ──────────────────────────────────────
c.band(218, 128, "入口链路（南北向 · §3.2.1）",
       "现状：7 个中间件端口映射宿主机，compose 内无 8080 / 443 与应用容器；设计层目标态：仅 443 经反向代理；真实客户端 IP 经 X-Forwarded-For 透传",
       "#fef2f2", "#dc2626", "暴露面现状")
ingress = [
    ("7 端口映射宿主机", "5432 / 6379 / 8180 / 9876 / 10911 / 16686 / 4317", "现状；无 8080 / 443 / 应用容器"),
    ("仅 443（设计层目标态）", "反向代理或 API 网关", "G6 前不落地；实证无 nginx 或网关实体"),
    ("BearerTokenFilter + 输入校验", "替代 WAF 控制", "无 WAF（O7），902004 限流码"),
]
for i, (p, t, s) in enumerate(ingress):
    c.node(60 + i * 440, 280, 420, 54, p, t, "#fee2e2", "#ef4444", tsize=11, ssize=8.8)
    c.lines(74 + i * 440, 340, [s], size=9.4, color="#991b1b")

# ── 内部互联带 ──────────────────────────────────────
c.band(366, 246, "内部互联链路（东西向 · §3.2.2）",
       "服务发现为 compose 服务名加 Spring Bean 注入；内部 mTLS 不启用；幂等键与 traceId 经 Header 透传；应用 tioka-server 为本地进程非 compose 容器",
       "#ecfdf5", "#059669", "东西向")
c.node(60, 428, 300, 60, "tioka-server（本地进程）", "15 域 api 与 biz 同进程；无硬编码 IP", "#ffffff", "#059669", tsize=11.5, ssize=9)
inner = [
    ("PostgreSQL 5432", "JDBC 连接池", "MyBatis-Plus 直连单实例"),
    ("Redis 6379", "RESP", "会话锁 rt:lock 与事件流"),
    ("Keycloak 8180", "OIDC", "授权码加 PKCE(S256)"),
    ("RocketMQ 9876 / 10911", "TCP 自定义协议", "namesrv 发现加 broker 投递"),
    ("Jaeger 4317", "OTLP gRPC", "OTel Agent 上报 trace"),
]
for i, (t, p, s) in enumerate(inner):
    col, row = i % 3, i // 3
    x = 390 + col * 330
    y = 418 + row * 92
    c.node(x, y, 314, 76, t, p, "#f0fdfa", "#0d9488", tsize=11, ssize=9)
    c.lines(x + 14, y + 56, [s], size=9.6, color="#065f46")
    c.arrow(360, 458, x, y + 34, color="#0f766e", sw=1.1, marker="ahg")

# ── 跨网络互联带 ────────────────────────────────────
c.band(632, 130, "跨网络互联（出口 · §3.2.3）",
       "出口方式为宿主机 NAT 或出口代理；固定出口 IP 用于飞书与模型 Provider 白名单；无跨 VPC、跨账号、跨 Region 通道",
       "#fffbeb", "#d97706", "出口")
egress = [
    ("E-01 至 E-05 出站", "公网 HTTPS", "tioka.egress.mode 在 prod 为 OPEN；deny-cidrs 拦元数据地址"),
    ("私有化数据不出境", "S0 硬边界", "跨地域或跨云出口不适用（O6）"),
    ("三分区（设计层规划）", "Docker bridge", "tioka-edge / tioka-app / tioka-data；现状默认 bridge，G6 前不落地"),
]
for i, (t, p, s) in enumerate(egress):
    c.node(60 + i * 440, 694, 420, 56, t, p, "#fef3c7", "#f59e0b", tsize=11, ssize=8.8)
    c.lines(74 + i * 440, 760, [s], size=9.4, color="#92400e")

# ── 连接边 ──────────────────────────────────────────
for i in range(4):
    x = 217 + i * 330
    c.arrow(x, 190, x, 218, color="#1d4ed8", sw=1.3, marker="ahb")
c.arrow(270, 334, 270, 366, color="#c2410c", sw=1.4, label="设计层入站", lx=316, ly=356, marker="aho")
c.arrow(700, 612, 700, 632, color="#d97706", sw=1.4, label="出站", lx=742, ly=628, marker="aho")

# ── 底部注释 ────────────────────────────────────────
c.rect(40, 790, W - 80, 92, "#ffffff", "#cbd5e1", rx=10, sw=1)
c.lines(58, 814, [
    "权威方：网络分区与拓扑命名由 platform-architect 判定；安全组与信任域规则由 security-architect 判定，两侧共用 G5 重叠区基线。映射宿主机不等于已对公网暴露，可达性待 U 系列取证。",
    "实证：compose.yml 未声明 networks 段、未声明 mTLS、未部署反向代理；application-prod.yml 的 deny-cidrs 为应用层出站拦截清单，非云子网 CIDR。",
], size=10.4, color="#475569", lh=25)

path = c.save(os.path.join(OUT, "network-links.svg"))
print("生成:", path)
