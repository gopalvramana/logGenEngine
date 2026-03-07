package com.loggenengine.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core log event produced by the simulation engine.
 * Every field is set at construction time; the event is effectively immutable.
 */
@Data
@Builder
public class LogEvent {
    /** Global monotonically increasing sequence number (deterministic). */
    private long sequenceNumber;

    /** Virtual timestamp in simulated time. */
    private Instant timestamp;

    private LogLevel level;
    private EventType eventType;

    /** Service name, e.g. "order-service" */
    private String service;

    /** Instance ID, e.g. "order-service-2" */
    private String instanceId;

    /** 16-char hex trace ID — shared across correlated events. */
    private String traceId;

    /** 8-char hex span ID — unique per event. */
    private String spanId;

    /** Parent span ID — null for root spans. */
    private String parentSpanId;

    /** Logger class name in abbreviated form, e.g. "c.l.e.OrderService" */
    private String logger;

    /** Thread name, e.g. "http-nio-8080-exec-3" */
    private String thread;

    /** Human-readable log message. */
    private String message;

    /** Structured context fields (method, path, statusCode, durationMs, etc.) */
    @Builder.Default
    private Map<String, Object> fields = new LinkedHashMap<>();

    /** Exception class name — present only for ERROR events. */
    private String exceptionClass;

    /** Abbreviated stack trace — present only for ERROR events. */
    private String stackTrace;
}
