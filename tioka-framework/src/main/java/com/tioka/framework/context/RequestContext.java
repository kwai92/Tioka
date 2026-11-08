package com.tioka.framework.context;

/**
 * 关联上下文（OBS-03 / ADR-073）：{@code request_id}、{@code subject}、{@code parent_request_id}。
 *
 * <p>trace 可断，request_id 不断；跨 HTTP / outbox / Turn / 线程池传递。
 */
public record RequestContext(String requestId, String subject, String parentRequestId) {

    public static RequestContext empty() {
        return new RequestContext(null, null, null);
    }

    public RequestContext withRequestId(String id) {
        return new RequestContext(id, subject, parentRequestId);
    }

    public RequestContext withSubject(String subject) {
        return new RequestContext(requestId, subject, parentRequestId);
    }

    public RequestContext withParentRequestId(String parent) {
        return new RequestContext(requestId, subject, parent);
    }
}
