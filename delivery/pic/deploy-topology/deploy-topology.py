#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图 1 · 部署物理拓扑图（G5 部署设计，v1.1 按主理人基线更正重绘）

场景识别：系统架构图 / 部署物理拓扑。工具选择：环境实测无 graphviz、diagrams、
matplotlib、cairosvg、Pillow，故选「Python 标准库零依赖 SVG 矢量」方案，与 G3 / G4
编排层已锁定的绘图方案一致，复用 pic/_svgkit.py 原语，不引入任何第三方依赖。

组件清单：外部接入层 3 类 / 宿主机边界 3 类 / 单 AZ 单实例 compose 三逻辑分区
（tioka-edge、tioka-app、tioka-data，均为设计层规划）/ 可观测与运维 4 类。
连接关系：现状 compose 将 7 个中间件端口映射宿主机，compose 内无 8080 / 443
与应用容器，应用由本地进程启动；设计层目标态仅 443 经反向代理入站（G6 前不落地）。
应用经 JDBC / RESP / OIDC / MQ / OTLP 访问数据分区；公网可达性待 U 系列取证。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _svgkit import Canvas  # noqa: E402

W, H = 1400, 1140
OUT = os.path.dirname(os.path.abspath(__file__))
c = Canvas(W, H,
           "Tioka 部署设计 · 图 1 部署物理拓扑",
           "单 Region / 单 AZ / 单实例私有化 compose；实测 6 个 service 定义 = 5 冻结中间件 + jaeger（GOV-08）；7 端口映射宿主机、无 8080 / 443 / 应用容器；三分区为设计层逻辑分区")

# ── 外部接入层 ──────────────────────────────────────
c.band(82, 126, "外部接入层（宿主机之外）",
       "浏览器与客户端、运维交付通道、外部依赖 E-01 至 E-05；无云 VPC、无云子网、无云安全组；公网可达性取决于宿主机 iptables 与客户侧策略，上游未登记",
       "#eff6ff", "#2563eb", "公网可达性待取证")
c.node(60, 144, 400, 54, "浏览器与客户端", "应用由本地进程启动监听 8080；设计层目标态仅 443", "#dbeafe", "#3b82f6", tsize=12, ssize=9)
c.node(480, 144, 400, 54, "运维与交付方 SRE", "私有化安装手册 W5-16 / scripts/accept.sh", "#dbeafe", "#3b82f6", tsize=12, ssize=9.5)
c.node(900, 144, 440, 54, "外部依赖 E-01 至 E-05", "Keycloak 外置 / 飞书 / 模型 Provider / MCP / IM（出站 HTTPS）", "#dbeafe", "#3b82f6", tsize=12, ssize=9)

# ── 宿主机边界 ──────────────────────────────────────
c.band(228, 132, "宿主机边界（反向代理与 TLS 终结点 · G4 §6.2 设计层目标态）",
       "实证：compose 内无 8080 / 443 与应用容器；全仓未检出 nginx 或 API 网关；替代控制为宿主机 iptables 加应用层管控",
       "#fef2f2", "#dc2626", "反向代理缺失")
c.node(60, 290, 400, 56, "反向代理 / API 网关（设计层目标态）", "仅 443 对外；TLS 终结、限流、X-Forwarded-For 透传", "#fee2e2", "#ef4444", tsize=11, ssize=9)
c.node(480, 290, 400, 56, "应用层管控（实证）", "BearerTokenFilter 加输入校验加 RestExceptionAdvice", "#fee2e2", "#ef4444", tsize=11.5, ssize=9)
c.node(900, 290, 440, 56, "宿主机 ACL（实证）", "iptables 与 compose 网络隔离；无云安全组", "#fee2e2", "#ef4444", tsize=11.5, ssize=9)

# ── 单 AZ 单实例 compose 大带 ────────────────────────
c.band(380, 430, "单 Region · 单 AZ · 单实例 Docker compose（首批私有化现状）",
       "deploy/compose.yml 实测 6 个 service 定义 = 5 冻结中间件 + jaeger；G5 设计层三分区 tioka-edge / tioka-app / tioka-data（现状未声明 networks 段）",
       "#f0fdfa", "#0d9488", "6 service 定义")

c.rect(60, 446, 260, 322, "#ecfeff", "#0891b2", rx=12, sw=1.6)
c.text(190, 474, "tioka-edge（设计层规划）", size=13, color="#0e7490", weight="bold", anchor="middle")
c.lines(76, 502, [
    "端口暴露面（现状实证）",
    "7 个端口映射宿主机：",
    "5432 / 6379 / 8180",
    "9876 / 10911 / 16686 / 4317",
    "compose 内无 8080 / 443",
    "无应用容器（本地进程启动）",
    "设计层目标态：仅 443 对外",
    "无 WAF（O7），限流加输入校验",
], size=10.2, color="#155e75", lh=29)

c.rect(340, 446, 520, 322, "#ecfdf5", "#059669", rx=12, sw=1.6)
c.text(600, 474, "tioka-app（设计层规划）", size=13, color="#047857", weight="bold", anchor="middle")
c.node(360, 490, 480, 54, "tioka-server（本地进程 · 8080）", "Java 17 / Spring Boot 3.2.12 / 15 域 api 与 biz 聚合；非 compose 容器", "#ffffff", "#059669", tsize=11, ssize=8.8)
c.node(360, 554, 480, 54, "tioka-ui（Vue 3 / Vite dev 5173）", "生产同域部署；本地经 Vite 代理 /api 同源", "#ffffff", "#059669", tsize=11.5, ssize=9)
c.node(360, 618, 480, 54, "OpenTelemetry Java Agent 1.33.5", "deploy/Dockerfile：仅 OTLP traces，metrics 与 logs 关闭", "#ffffff", "#059669", tsize=11.5, ssize=9)
c.lines(360, 694, [
    "健康检查：Actuator health 与 info（实证）；G4 §5.2.1 的 /healthz 与",
    "/readyz 在 tioka-server 主源零命中，登记为漂移。",
    "mock 开关：local 下 model.mock=true 与 mcp.mock=true；",
    "fidelity 与 prod 两份 profile 均为 false。",
], size=10.2, color="#065f46", lh=22)

c.rect(880, 446, 460, 322, "#f5f3ff", "#7c3aed", rx=12, sw=1.6)
c.text(1110, 474, "tioka-data（设计层规划）", size=13, color="#6d28d9", weight="bold", anchor="middle")
data_nodes = [
    ("postgres pgvector:pg16", "5432 · 卷 tioka_pg · pg_isready"),
    ("redis:7-alpine", "6379 · redis-cli ping"),
    ("keycloak:24.0", "8180 · start-dev 导入 realm"),
    ("rocketmq-namesrv 5.3.1", "9876 · 无 healthcheck"),
    ("rocketmq-broker 5.3.1", "10911 · NAMESRV_ADDR 指向 namesrv"),
    ("jaeger all-in-one 1.57", "16686 / 4317 · GOV-08 第 6 service"),
]
for i, (t, s) in enumerate(data_nodes):
    col, row = i % 2, i // 2
    c.node(896 + col * 220, 490 + row * 66, 208, 58, t, s,
           "#ffffff", "#7c3aed", tsize=10.2, ssize=8.2)
c.lines(896, 700, [
    "实证：compose.yml 未声明 networks 段，6 个 service",
    "当前处于 Docker 默认 bridge。三分区为 G5 设计层",
    "逻辑分区，G6 前不落地，收敛动作归 G6 后。",
    "jaeger 容器已存在 ≠ 全量 OTel trace 能力已交付（P2）。",
], size=10.2, color="#5b21b6", lh=22)

# ── 连接边 ──────────────────────────────────────────
c.arrow(260, 198, 260, 228, color="#1d4ed8", sw=1.6, label="设计层入站", lx=290, ly=220, marker="ahb")
c.arrow(600, 198, 600, 228, color="#475569", sw=1.4, label="运维通道", lx=660, ly=220)
c.arrow(1120, 198, 1120, 228, color="#c2410c", sw=1.4, label="出站 HTTPS", lx=1120, ly=220, marker="aho")
c.arrow(600, 346, 600, 380, color="#0f766e", sw=1.8, label="鉴权后内网 HTTP", lx=700, ly=370, marker="ahg")
c.arrow(600, 490, 600, 446, color="#0f766e", sw=1.4, marker="ahg")
c.arrow(840, 560, 880, 560, color="#6d28d9", sw=1.5, label="JDBC / RESP / OIDC / MQ", lx=860, ly=550, marker="ahb")
c.arrow(1100, 618, 1100, 800, color="#9e9e9e", sw=1.2, dash="5,4", label="OTLP 4317", lx=1160, ly=730)

# ── 可观测与运维带 ──────────────────────────────────
c.band(828, 140, "可观测与运维（G4 §8 设计基线；部署侧仅 jaeger 已落地）",
       "6 个 Dashboard 与 7 条 P0 至 P3 告警为设计基线，首批无 Prometheus / Grafana / CLS 容器，登记为规划项",
       "#fffbeb", "#d97706", "设计基线")
obs = [
    ("Jaeger UI 16686（实证）", "OTLP gRPC 4317 收 trace"),
    ("6 个 Dashboard（设计基线）", "业务 / 服务健康 / MQ 闭环 / 中间件 / 容量 / 预警"),
    ("7 条告警 AL-01 至 AL-07", "P0 至 P3 全带 Owner 与 Runbook"),
    ("备份与恢复", "RPO ≤ 5 分钟 · RTO ≤ 30 分钟 · PG 逻辑或物理备份"),
]
for i, (t, s) in enumerate(obs):
    c.node(52 + i * 330, 890, 314, 56, t, s, "#fef3c7", "#f59e0b", tsize=10.8, ssize=8.6)

# ── 底部注释 ────────────────────────────────────────
c.rect(40, 990, W - 80, 126, "#ffffff", "#cbd5e1", rx=10, sw=1)
c.lines(58, 1014, [
    "边界继承：O7 冻结 5 个中间件、首批不新增；jaeger 为既有代码事实非 MVP 新中间件，不违反 O7；O1 永久排除云托管 Agent 执行面；O6 排除跨租户 SaaS；O5 G6 前不动生产代码。",
    "实证口径：6 个 service 定义、镜像、7 个端口映射、卷、healthcheck、mock 开关逐字取自 deploy/compose.yml 与 tioka-server/src/main/resources 下四份 yml；8080 与 443 在 compose 中零命中。",
    "实证声明：现状 compose 无 networks 段、7 个端口均映射宿主机、无 8080 / 443 与应用容器；三分区为设计层规划、G6 前不落地；差异登记于 §2.6 四态表 DEP-02 / DEP-19（六服务 vs 五服务漏登）/ DEP-20（端口暴露面收敛建议）。",
    "高可用：本期为单 AZ 单实例，双实例 HA 与 Keycloak 非 mock 验收属 O4 Out-of-Scope，完整版演进路径见图 3，不以本期已实现或已承诺表述。",
], size=10.4, color="#475569", lh=25)

path = c.save(os.path.join(OUT, "deploy-topology.svg"))
print("生成:", path)
