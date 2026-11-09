#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tioka 系统设计图示 · 零依赖 SVG 绘图原语（Python 标准库）。

场景识别：系统架构图 / 分层模块图 / 关键时序图 / 状态机图。
工具选择：环境实测无 graphviz、diagrams、matplotlib、cairosvg、Pillow，
         故采用「Mermaid 内嵌 + Python 标准库零依赖 SVG 矢量」组合，
         与 G3 编排层已锁定的工具方案一致，不引入任何第三方依赖。
本文件被同目录 4 个绘图脚本以 sys.path 方式复用。
"""

FONT = "PingFang SC, Hiragino Sans GB, Microsoft YaHei, Noto Sans CJK SC, sans-serif"


class Canvas:
    def __init__(self, w, h, title, subtitle):
        self.w, self.h = w, h
        self.p = []
        self.p.append(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" font-family="{FONT}">'
        )
        self.p.append(
            '<defs>'
            '<marker id="ah" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
            'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#475569"/></marker>'
            '<marker id="ahb" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
            'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#1d4ed8"/></marker>'
            '<marker id="ahg" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
            'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#0f766e"/></marker>'
            '<marker id="aho" markerWidth="9" markerHeight="9" refX="8" refY="4.5" '
            'orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#c2410c"/></marker>'
            '</defs>'
        )
        self.p.append(f'<rect width="{w}" height="{h}" fill="#f8fafc"/>')
        self.text(40, 38, title, size=22, color="#0f172a", weight="bold")
        self.text(40, 61, subtitle, size=11.5, color="#64748b")

    @staticmethod
    def esc(s):
        return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    def rect(self, x, y, w, h, fill, stroke, rx=8, sw=1.2, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.p.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}"{d}/>'
        )

    def text(self, x, y, s, size=13, color="#1f2937", weight="normal", anchor="start"):
        self.p.append(
            f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
            f'fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{self.esc(s)}</text>'
        )

    def lines(self, x, y, arr, size=11.5, color="#334155", lh=17, anchor="start", weight="normal"):
        for i, s in enumerate(arr):
            self.text(x, y + i * lh, s, size=size, color=color, anchor=anchor, weight=weight)

    def node(self, x, y, w, h, title, sub, fill, stroke, tsize=13, ssize=10.5, tcolor="#111827"):
        self.rect(x, y, w, h, fill, stroke, rx=7, sw=1.1)
        self.text(x + w / 2, y + (h / 2 if not sub else h / 2 - 5), title,
                  size=tsize, color=tcolor, weight="bold", anchor="middle")
        if sub:
            self.text(x + w / 2, y + h / 2 + 13, sub, size=ssize, color="#4b5563", anchor="middle")

    def arrow(self, x1, y1, x2, y2, color="#475569", sw=1.6, dash=None, label=None,
              lx=None, ly=None, lsize=10.5, marker="ah"):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.p.append(
            f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
            f'stroke-width="{sw}"{d} marker-end="url(#{marker})"/>'
        )
        if label:
            self.text(lx if lx is not None else (x1 + x2) / 2,
                      ly if ly is not None else (y1 + y2) / 2 - 6,
                      label, size=lsize, color=color, anchor="middle")

    def band(self, y, h, title, subtitle, fill, stroke, badge, x=40, w=None):
        w = w if w is not None else self.w - 80
        self.rect(x, y, w, h, fill, stroke, rx=14, sw=1.6)
        self.rect(x, y, 8, h, stroke, stroke, rx=3, sw=0)
        self.text(x + 26, y + 30, title, size=17, color="#111827", weight="bold")
        self.text(x + 26, y + 51, subtitle, size=11.5, color="#6b7280")
        self.rect(x + w - 232, y + 16, 176, 26, stroke, stroke, rx=13, sw=0)
        self.text(x + w - 144, y + 34, badge, size=11, color="#ffffff", weight="bold", anchor="middle")

    def save(self, path):
        self.p.append('</svg>')
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(self.p))
        return path
