package com.tioka.connector.api.spi;

/**
 * 飞书文档正文拉取 SPI（knowledge 仅依赖 api，避免 biz→biz）。
 */
public interface FeishuDocContentSpi {
    /** @return raw 文本；不可用时返回空串 */
    String rawContent(String feishuDocToken);
}
