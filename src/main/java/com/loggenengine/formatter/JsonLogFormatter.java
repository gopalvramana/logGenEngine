package com.loggenengine.formatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loggenengine.model.LogEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Formats a LogEvent as a single JSON object (for JSONL output).
 * All fields included; Instant serialized as ISO-8601 string.
 */
@Component
public class JsonLogFormatter implements LogFormatter {

    private final ObjectMapper mapper;

    public JsonLogFormatter() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String format(LogEvent event) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("timestamp",     event.getTimestamp().toString());
            doc.put("seq",           event.getSequenceNumber());
            doc.put("level",         event.getLevel().name());
            doc.put("eventType",     event.getEventType().name());
            doc.put("service",       event.getService());
            doc.put("instanceId",    event.getInstanceId());
            doc.put("traceId",       event.getTraceId());
            doc.put("spanId",        event.getSpanId());
            if (event.getParentSpanId() != null) {
                doc.put("parentSpanId", event.getParentSpanId());
            }
            doc.put("logger",        event.getLogger());
            doc.put("thread",        event.getThread());
            doc.put("message",       event.getMessage());
            if (event.getFields() != null && !event.getFields().isEmpty()) {
                doc.put("fields", event.getFields());
            }
            if (event.getExceptionClass() != null) {
                doc.put("exceptionClass", event.getExceptionClass());
            }
            if (event.getStackTrace() != null) {
                doc.put("stackTrace", event.getStackTrace());
            }
            return mapper.writeValueAsString(doc);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\",\"message\":\"" + event.getMessage() + "\"}";
        }
    }
}
