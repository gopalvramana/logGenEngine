package com.loggenengine.generators;

import com.loggenengine.core.DeterministicRandom;
import com.loggenengine.model.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates realistic HTTP access log events.
 */
@Component
public class HttpRequestLogGenerator implements LogEventGenerator {

    private static final List<String[]> ENDPOINTS = List.of(
            new String[]{"GET",    "/api/orders",                  "200", "application/json"},
            new String[]{"GET",    "/api/orders/{id}",             "200", "application/json"},
            new String[]{"POST",   "/api/orders",                  "201", "application/json"},
            new String[]{"PUT",    "/api/orders/{id}/status",      "200", "application/json"},
            new String[]{"DELETE", "/api/orders/{id}",             "204", "application/json"},
            new String[]{"GET",    "/api/users/{id}",              "200", "application/json"},
            new String[]{"POST",   "/api/users/login",             "200", "application/json"},
            new String[]{"GET",    "/api/products",                "200", "application/json"},
            new String[]{"GET",    "/api/products/{id}",           "200", "application/json"},
            new String[]{"POST",   "/api/cart/items",              "201", "application/json"},
            new String[]{"GET",    "/api/cart",                    "200", "application/json"},
            new String[]{"POST",   "/api/payments",                "201", "application/json"},
            new String[]{"GET",    "/api/inventory/{productId}",   "200", "application/json"},
            new String[]{"GET",    "/health",                      "200", "application/json"},
            new String[]{"GET",    "/actuator/metrics",            "200", "application/json"}
    );

    private static final List<String> CLIENT_IPS = List.of(
            "10.0.1.101", "10.0.1.102", "10.0.2.55", "172.16.0.45",
            "192.168.1.200", "192.168.1.201", "10.100.0.5", "10.100.0.6"
    );

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
            "okhttp/4.11.0",
            "axios/1.4.0",
            "python-requests/2.31.0",
            "Go-http-client/1.1"
    );

    private static final List<Integer> ERROR_STATUS = List.of(400, 401, 403, 404, 422, 429, 500, 502, 503);

    @Override
    public EventType getEventType() {
        return EventType.HTTP_REQUEST;
    }

    @Override
    public List<LogEvent> generate(ServiceNode node, TraceContext trace, Instant timestamp,
                                   DeterministicRandom rng, AtomicLong sequenceCounter) {

        String[] endpoint = rng.nextElement(ENDPOINTS);
        String method = endpoint[0];
        String pathTemplate = endpoint[1];
        String clientIp = rng.nextElement(CLIENT_IPS);
        String userAgent = rng.nextElement(USER_AGENTS);

        // Replace path params with deterministic IDs
        String path = pathTemplate
                .replace("{id}", String.valueOf(rng.nextLongBetween(1000, 999999)))
                .replace("{productId}", "PROD-" + rng.nextIntBetween(1, 500));

        boolean isError = rng.nextBoolean(node.getErrorRate());
        int statusCode = isError
                ? rng.nextElement(ERROR_STATUS)
                : Integer.parseInt(endpoint[2]);

        // Response time: health endpoints are fast, others vary
        long durationMs = path.contains("health") || path.contains("actuator")
                ? rng.nextIntBetween(1, 5)
                : (long) rng.nextGaussianPositive(isError ? 800 : 120, isError ? 400 : 80);

        // User ID — sometimes absent (unauthenticated requests)
        String userId = rng.nextBoolean(0.85)
                ? "usr-" + rng.nextIntBetween(1000, 99999)
                : null;

        LogLevel level = statusCode >= 500 ? LogLevel.ERROR
                       : statusCode >= 400 ? LogLevel.WARN
                       : LogLevel.INFO;

        String message = String.format("%s %s %d %dms", method, path, statusCode, durationMs);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", method);
        fields.put("path", path);
        fields.put("statusCode", statusCode);
        fields.put("durationMs", durationMs);
        fields.put("clientIp", clientIp);
        fields.put("userAgent", userAgent);
        if (userId != null) fields.put("userId", userId);
        fields.put("contentType", endpoint[3]);

        long jitter = rng.nextLongBetween(0, 999);

        LogEvent event = LogEvent.builder()
                .sequenceNumber(sequenceCounter.getAndIncrement())
                .timestamp(timestamp.plusMillis(jitter))
                .level(level)
                .eventType(EventType.HTTP_REQUEST)
                .service(node.getName())
                .instanceId(node.getInstanceId())
                .traceId(trace.traceId())
                .spanId(trace.spanId())
                .parentSpanId(trace.parentSpanId())
                .logger("c.l.e." + capitalize(toCamel(node.getName())) + "Filter")
                .thread("http-nio-8080-exec-" + rng.nextIntBetween(1, 20))
                .message(message)
                .fields(fields)
                .build();

        return List.of(event);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toCamel(String kebab) {
        String[] parts = kebab.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(capitalize(parts[i]));
        }
        return sb.toString();
    }
}
