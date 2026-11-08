package com.tioka.infra.api.notify;

import com.tioka.framework.label.DataLabel;

import java.util.List;
import java.util.Map;

/**
 * 通知内容（M3-03 NTF-01）。块/按钮用最小 Map 表示，避免依赖 connector IM 模型。
 */
public record Notice(
        String noticeKey,
        String title,
        List<Map<String, Object>> blocks,
        List<Map<String, Object>> actions,
        DataLabel label
) {}
