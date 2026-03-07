package com.loggenengine.model;

/**
 * Immutable distributed tracing context.
 * A root span has parentSpanId == null.
 */
public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId
) {
    public static TraceContext root(String traceId, String spanId) {
        return new TraceContext(traceId, spanId, null);
    }

    public TraceContext childSpan(String childSpanId) {
        return new TraceContext(traceId, childSpanId, spanId);
    }

    public boolean isRoot() {
        return parentSpanId == null;
    }
}
