package com.tioka.connector.api.im;

/** 发送目标。 */
public record ImTarget(String chatId, String userId, String receiveIdType) {}
