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
 * Generates realistic application-level log events: business logic,
 * cache operations, scheduled jobs, health checks, metrics, etc.
 */
@Component
public class ApplicationLogGenerator implements LogEventGenerator {

    @Override
    public EventType getEventType() {
        return EventType.APPLICATION;
    }

    @Override
    public List<LogEvent> generate(ServiceNode node, TraceContext trace, Instant timestamp,
                                   DeterministicRandom rng, AtomicLong sequenceCounter) {

        // Pick an event template appropriate to the service type
        AppEvent template = pickTemplate(node, rng);
        long jitter = rng.nextLongBetween(0, 999);

        LogEvent event = LogEvent.builder()
                .sequenceNumber(sequenceCounter.getAndIncrement())
                .timestamp(timestamp.plusMillis(jitter))
                .level(template.level)
                .eventType(EventType.APPLICATION)
                .service(node.getName())
                .instanceId(node.getInstanceId())
                .traceId(trace.traceId())
                .spanId(trace.spanId())
                .parentSpanId(trace.parentSpanId())
                .logger(template.logger)
                .thread(template.thread)
                .message(template.message)
                .fields(template.fields)
                .build();

        return List.of(event);
    }

    private AppEvent pickTemplate(ServiceNode node, DeterministicRandom rng) {
        return switch (node.getName()) {
            case "order-service"     -> orderServiceEvent(rng);
            case "payment-service"   -> paymentServiceEvent(rng);
            case "inventory-service" -> inventoryServiceEvent(rng);
            case "user-service"      -> userServiceEvent(rng);
            case "api-gateway"       -> gatewayEvent(rng);
            case "redis-cache"       -> cacheEvent(rng);
            default                  -> genericAppEvent(node, rng);
        };
    }

    private AppEvent orderServiceEvent(DeterministicRandom rng) {
        int choice = rng.nextInt(6);
        return switch (choice) {
            case 0 -> {
                String orderId = "ORD-" + rng.nextIntBetween(100000, 999999);
                String custId  = "cust-" + rng.nextIntBetween(1000, 99999);
                double amount  = Math.round(rng.nextGaussianPositive(89.99, 60) * 100) / 100.0;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("orderId", orderId); f.put("customerId", custId); f.put("amount", amount);
                f.put("itemCount", rng.nextIntBetween(1, 8));
                yield new AppEvent(LogLevel.INFO, "c.l.e.OrderService",
                        "http-nio-8080-exec-" + rng.nextIntBetween(1, 20),
                        "Processing order " + orderId + " for customer " + custId, f);
            }
            case 1 -> {
                String orderId = "ORD-" + rng.nextIntBetween(100000, 999999);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("orderId", orderId); f.put("status", "CONFIRMED");
                f.put("processingTimeMs", rng.nextIntBetween(50, 500));
                yield new AppEvent(LogLevel.INFO, "c.l.e.OrderService",
                        "order-processor-" + rng.nextIntBetween(1, 4),
                        "Order " + orderId + " confirmed and queued for fulfillment", f);
            }
            case 2 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("jobName", "OrderCleanupJob"); f.put("deletedCount", rng.nextIntBetween(0, 50));
                f.put("durationMs", rng.nextIntBetween(100, 5000));
                yield new AppEvent(LogLevel.INFO, "c.l.e.OrderCleanupJob",
                        "scheduled-job-1", "Cleanup job completed: removed expired draft orders", f);
            }
            case 3 -> {
                String orderId = "ORD-" + rng.nextIntBetween(100000, 999999);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("orderId", orderId); f.put("cacheKey", "order:" + orderId); f.put("hit", false);
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.OrderCacheService",
                        "http-nio-8080-exec-" + rng.nextIntBetween(1, 20),
                        "Cache miss for order " + orderId + ", fetching from database", f);
            }
            case 4 -> {
                String orderId = "ORD-" + rng.nextIntBetween(100000, 999999);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("orderId", orderId); f.put("retryCount", rng.nextIntBetween(1, 3));
                yield new AppEvent(LogLevel.WARN, "c.l.e.OrderFulfillmentService",
                        "fulfillment-worker-" + rng.nextIntBetween(1, 3),
                        "Retrying fulfillment for order " + orderId, f);
            }
            default -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("activeOrders", rng.nextIntBetween(100, 5000));
                f.put("queueDepth", rng.nextIntBetween(0, 200));
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.OrderMetrics",
                        "metrics-reporter", "Order metrics snapshot", f);
            }
        };
    }

    private AppEvent paymentServiceEvent(DeterministicRandom rng) {
        int choice = rng.nextInt(5);
        return switch (choice) {
            case 0 -> {
                String txId = "TXN-" + rng.nextHex(12).toUpperCase();
                double amount = Math.round(rng.nextGaussianPositive(89.99, 60) * 100) / 100.0;
                String gateway = rng.nextElement(List.of("stripe", "paypal", "braintree"));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("transactionId", txId); f.put("amount", amount);
                f.put("gateway", gateway); f.put("currency", "USD");
                yield new AppEvent(LogLevel.INFO, "c.l.e.PaymentProcessor",
                        "payment-executor-" + rng.nextIntBetween(1, 4),
                        "Payment " + txId + " processed via " + gateway, f);
            }
            case 1 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("pendingPayments", rng.nextIntBetween(0, 30));
                f.put("successRate24h", Math.round(rng.nextGaussianPositive(97.5, 1.5) * 10) / 10.0);
                yield new AppEvent(LogLevel.INFO, "c.l.e.PaymentMetrics",
                        "metrics-reporter", "Payment gateway health check passed", f);
            }
            case 2 -> {
                String refundId = "REF-" + rng.nextHex(10).toUpperCase();
                double amount = Math.round(rng.nextGaussianPositive(45.0, 30) * 100) / 100.0;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("refundId", refundId); f.put("amount", amount); f.put("reason", "customer_request");
                yield new AppEvent(LogLevel.INFO, "c.l.e.RefundService",
                        "refund-processor-1", "Refund " + refundId + " initiated for $" + amount, f);
            }
            case 3 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("fraudScore", Math.round(rng.nextGaussianPositive(15, 10) * 10) / 10.0);
                f.put("action", "ALLOW");
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.FraudDetectionService",
                        "fraud-checker-1", "Fraud check completed", f);
            }
            default -> {
                String webhookId = "wh_" + rng.nextHex(16);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("webhookId", webhookId); f.put("event", "payment.succeeded");
                yield new AppEvent(LogLevel.INFO, "c.l.e.WebhookHandler",
                        "webhook-consumer-1", "Webhook received: payment.succeeded", f);
            }
        };
    }

    private AppEvent inventoryServiceEvent(DeterministicRandom rng) {
        int choice = rng.nextInt(4);
        return switch (choice) {
            case 0 -> {
                String productId = "PROD-" + rng.nextIntBetween(1, 500);
                int qty = rng.nextIntBetween(1, 20);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("productId", productId); f.put("quantity", qty);
                f.put("warehouse", "WH-" + rng.nextIntBetween(1, 3));
                yield new AppEvent(LogLevel.INFO, "c.l.e.InventoryService",
                        "inventory-worker-" + rng.nextIntBetween(1, 3),
                        "Reserved " + qty + " units of " + productId, f);
            }
            case 1 -> {
                String productId = "PROD-" + rng.nextIntBetween(1, 500);
                int stock = rng.nextIntBetween(0, 10);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("productId", productId); f.put("currentStock", stock);
                yield new AppEvent(LogLevel.WARN, "c.l.e.StockAlertService",
                        "stock-monitor", "Low stock alert for " + productId + ": " + stock + " units remaining", f);
            }
            case 2 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("productsScanned", rng.nextIntBetween(100, 2000));
                f.put("lowStockCount", rng.nextIntBetween(0, 50));
                f.put("durationMs", rng.nextIntBetween(500, 10000));
                yield new AppEvent(LogLevel.INFO, "c.l.e.InventoryAuditJob",
                        "scheduled-job-1", "Daily inventory audit completed", f);
            }
            default -> {
                String productId = "PROD-" + rng.nextIntBetween(1, 500);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("productId", productId); f.put("cacheHit", rng.nextBoolean(0.75));
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.InventoryCacheService",
                        "http-nio-8080-exec-" + rng.nextIntBetween(1, 10),
                        "Inventory cache lookup for " + productId, f);
            }
        };
    }

    private AppEvent userServiceEvent(DeterministicRandom rng) {
        int choice = rng.nextInt(4);
        return switch (choice) {
            case 0 -> {
                String userId = "usr-" + rng.nextIntBetween(1000, 99999);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("userId", userId); f.put("sessionId", "sess-" + rng.nextHex(16));
                yield new AppEvent(LogLevel.INFO, "c.l.e.AuthService",
                        "http-nio-8080-exec-" + rng.nextIntBetween(1, 10),
                        "User " + userId + " authenticated successfully", f);
            }
            case 1 -> {
                String userId = "usr-" + rng.nextIntBetween(1000, 99999);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("userId", userId); f.put("profileField", rng.nextElement(List.of("email", "address", "preferences")));
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.UserProfileService",
                        "http-nio-8080-exec-" + rng.nextIntBetween(1, 10),
                        "Profile updated for user " + userId, f);
            }
            case 2 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("expiredSessions", rng.nextIntBetween(0, 100));
                yield new AppEvent(LogLevel.INFO, "c.l.e.SessionCleanupJob",
                        "scheduled-job-1", "Expired sessions purged", f);
            }
            default -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("activeUsers", rng.nextIntBetween(500, 10000));
                f.put("newRegistrations24h", rng.nextIntBetween(10, 500));
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.UserMetrics",
                        "metrics-reporter", "User metrics snapshot", f);
            }
        };
    }

    private AppEvent gatewayEvent(DeterministicRandom rng) {
        int choice = rng.nextInt(3);
        return switch (choice) {
            case 0 -> {
                String upstream = rng.nextElement(List.of("order-service", "payment-service", "user-service", "inventory-service"));
                long latency = rng.nextLongBetween(1, 50);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("upstream", upstream); f.put("latencyMs", latency);
                yield new AppEvent(LogLevel.DEBUG, "c.l.e.LoadBalancer",
                        "lb-worker-1", "Routing request to " + upstream + " (latency " + latency + "ms)", f);
            }
            case 1 -> {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("rps", rng.nextIntBetween(50, 200)); f.put("p99LatencyMs", rng.nextIntBetween(50, 800));
                yield new AppEvent(LogLevel.INFO, "c.l.e.GatewayMetrics",
                        "metrics-reporter", "Gateway metrics: healthy", f);
            }
            default -> {
                String ip = rng.nextElement(List.of("203.0.113.5", "198.51.100.42", "192.0.2.17"));
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("clientIp", ip); f.put("requestCount1m", rng.nextIntBetween(60, 200));
                yield new AppEvent(LogLevel.WARN, "c.l.e.RateLimiter",
                        "rate-limit-checker", "Rate limit threshold approaching for " + ip, f);
            }
        };
    }

    private AppEvent cacheEvent(DeterministicRandom rng) {
        boolean hit = rng.nextBoolean(0.8);
        String key = rng.nextElement(List.of("user:profile:", "order:", "product:stock:", "session:")) +
                     rng.nextIntBetween(1000, 99999);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key); f.put("hit", hit); f.put("ttlSeconds", rng.nextIntBetween(60, 3600));
        return new AppEvent(hit ? LogLevel.DEBUG : LogLevel.DEBUG, "c.l.e.CacheManager",
                "cache-io-" + rng.nextIntBetween(1, 8),
                (hit ? "Cache hit" : "Cache miss") + " for key " + key, f);
    }

    private AppEvent genericAppEvent(ServiceNode node, DeterministicRandom rng) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("service", node.getName());
        return new AppEvent(LogLevel.INFO, "c.l.e.Application",
                "main", node.getName() + " heartbeat OK", f);
    }

    /**
     * Emits a single "order fulfilled" completion event on the order-service span,
     * logged after all downstream calls (user-service, inventory-service, payment-service)
     * have returned. Other services are terminal and return nothing.
     */
    @Override
    public List<LogEvent> generateCompletion(ServiceNode node, TraceContext trace,
                                             Instant timestamp, DeterministicRandom rng,
                                             AtomicLong sequenceCounter) {
        if (!"order-service".equals(node.getName())) return List.of();

        String orderId   = "ORD-" + rng.nextIntBetween(100000, 999999);
        long durationMs  = rng.nextLongBetween(150, 1200);
        String custId    = "cust-" + rng.nextIntBetween(1000, 99999);

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("orderId",        orderId);
        f.put("customerId",     custId);
        f.put("status",         "COMPLETED");
        f.put("totalDurationMs", durationMs);
        f.put("steps",          List.of("user-validated", "inventory-reserved", "payment-charged"));

        long jitter = rng.nextLongBetween(0, 999);

        LogEvent event = LogEvent.builder()
                .sequenceNumber(sequenceCounter.getAndIncrement())
                .timestamp(timestamp.plusMillis(jitter))
                .level(LogLevel.INFO)
                .eventType(EventType.APPLICATION)
                .service(node.getName())
                .instanceId(node.getInstanceId())
                .traceId(trace.traceId())
                .spanId(trace.spanId())
                .parentSpanId(trace.parentSpanId())
                .logger("c.l.e.OrderService")
                .thread("http-nio-8080-exec-" + rng.nextIntBetween(1, 20))
                .message("Order " + orderId + " fulfilled in " + durationMs
                         + "ms — user validated, inventory reserved, payment charged")
                .fields(f)
                .build();

        return List.of(event);
    }

    private record AppEvent(LogLevel level, String logger, String thread,
                            String message, Map<String, Object> fields) {}
}
