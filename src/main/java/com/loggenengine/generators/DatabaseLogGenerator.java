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
 * Generates realistic database query log events including slow queries.
 */
@Component
public class DatabaseLogGenerator implements LogEventGenerator {

    private static final List<String[]> QUERY_TEMPLATES = List.of(
            new String[]{"SELECT", "orders",     "SELECT * FROM orders WHERE customer_id=?"},
            new String[]{"SELECT", "orders",     "SELECT id, status, amount FROM orders WHERE id=?"},
            new String[]{"SELECT", "users",      "SELECT * FROM users WHERE email=?"},
            new String[]{"SELECT", "users",      "SELECT id, name, email FROM users WHERE id=?"},
            new String[]{"SELECT", "products",   "SELECT * FROM products WHERE category=? LIMIT ? OFFSET ?"},
            new String[]{"SELECT", "inventory",  "SELECT quantity FROM inventory WHERE product_id=? FOR UPDATE"},
            new String[]{"INSERT", "orders",     "INSERT INTO orders (id, customer_id, amount, status) VALUES (?, ?, ?, ?)"},
            new String[]{"INSERT", "order_items","INSERT INTO order_items (order_id, product_id, qty, price) VALUES (?, ?, ?, ?)"},
            new String[]{"UPDATE", "orders",     "UPDATE orders SET status=?, updated_at=? WHERE id=?"},
            new String[]{"UPDATE", "inventory",  "UPDATE inventory SET quantity=quantity-? WHERE product_id=?"},
            new String[]{"UPDATE", "users",      "UPDATE users SET last_login=? WHERE id=?"},
            new String[]{"DELETE", "sessions",   "DELETE FROM sessions WHERE expires_at < ?"},
            new String[]{"SELECT", "payments",   "SELECT * FROM payments WHERE order_id=? ORDER BY created_at DESC"},
            new String[]{"INSERT", "payments",   "INSERT INTO payments (id, order_id, amount, gateway, status) VALUES (?, ?, ?, ?, ?)"},
            new String[]{"SELECT", "audit_log",  "SELECT * FROM audit_log WHERE entity_id=? AND entity_type=? LIMIT 50"}
    );

    @Override
    public EventType getEventType() {
        return EventType.DATABASE;
    }

    @Override
    public List<LogEvent> generate(ServiceNode node, TraceContext trace, Instant timestamp,
                                   DeterministicRandom rng, AtomicLong sequenceCounter) {

        String[] template = rng.nextElement(QUERY_TEMPLATES);
        String queryType = template[0];
        String table = template[1];
        String sql = template[2];

        boolean isSlow = rng.nextBoolean(node.getSlowQueryRate());
        long durationMs = isSlow
                ? rng.nextLongBetween(1000, 15000)
                : (long) rng.nextGaussianPositive(queryType.equals("SELECT") ? 12 : 25, 8);

        int rowsAffected = switch (queryType) {
            case "SELECT" -> rng.nextIntBetween(0, 500);
            case "INSERT" -> 1;
            case "UPDATE" -> rng.nextIntBetween(0, 5);
            case "DELETE" -> rng.nextIntBetween(0, 100);
            default -> 0;
        };

        LogLevel level = isSlow ? LogLevel.WARN : LogLevel.DEBUG;
        String message = isSlow
                ? String.format("Slow query detected [%dms]: %s", durationMs, sql)
                : String.format("%s on %s executed in %dms, rows=%d", queryType, table, durationMs, rowsAffected);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("queryType", queryType);
        fields.put("table", table);
        fields.put("durationMs", durationMs);
        fields.put("rowsAffected", rowsAffected);
        fields.put("slow", isSlow);
        if (isSlow) {
            fields.put("explainNeeded", true);
            fields.put("sql", sql);
        }

        // Connection pool stats — added occasionally
        if (rng.nextBoolean(0.1)) {
            fields.put("poolActive", rng.nextIntBetween(1, 20));
            fields.put("poolIdle", rng.nextIntBetween(0, 10));
            fields.put("poolTotal", 30);
        }

        long jitter = rng.nextLongBetween(0, 999);

        LogEvent event = LogEvent.builder()
                .sequenceNumber(sequenceCounter.getAndIncrement())
                .timestamp(timestamp.plusMillis(jitter))
                .level(level)
                .eventType(EventType.DATABASE)
                .service(node.getName())
                .instanceId(node.getInstanceId())
                .traceId(trace.traceId())
                .spanId(trace.spanId())
                .parentSpanId(trace.parentSpanId())
                .logger("c.l.e.DataSourceProxy")
                .thread("db-pool-" + rng.nextIntBetween(1, 10))
                .message(message)
                .fields(fields)
                .build();

        return List.of(event);
    }
}
