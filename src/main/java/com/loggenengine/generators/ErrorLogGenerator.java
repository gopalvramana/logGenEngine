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
 * Generates realistic ERROR log events with abbreviated stack traces.
 */
@Component
public class ErrorLogGenerator implements LogEventGenerator {

    @Override
    public EventType getEventType() {
        return EventType.ERROR;
    }

    @Override
    public List<LogEvent> generate(ServiceNode node, TraceContext trace, Instant timestamp,
                                   DeterministicRandom rng, AtomicLong sequenceCounter) {

        ErrorTemplate tmpl = pickTemplate(node, rng);
        long jitter = rng.nextLongBetween(0, 999);

        LogEvent event = LogEvent.builder()
                .sequenceNumber(sequenceCounter.getAndIncrement())
                .timestamp(timestamp.plusMillis(jitter))
                .level(LogLevel.ERROR)
                .eventType(EventType.ERROR)
                .service(node.getName())
                .instanceId(node.getInstanceId())
                .traceId(trace.traceId())
                .spanId(trace.spanId())
                .parentSpanId(trace.parentSpanId())
                .logger(tmpl.logger)
                .thread(tmpl.thread)
                .message(tmpl.message)
                .fields(tmpl.fields)
                .exceptionClass(tmpl.exceptionClass)
                .stackTrace(tmpl.stackTrace)
                .build();

        return List.of(event);
    }

    private ErrorTemplate pickTemplate(ServiceNode node, DeterministicRandom rng) {
        int choice = rng.nextInt(7);
        return switch (choice) {
            case 0 -> timeoutError(node, rng);
            case 1 -> nullPointerError(node, rng);
            case 2 -> dbConnectionError(node, rng);
            case 3 -> validationError(node, rng);
            case 4 -> outOfMemoryError(node, rng);
            case 5 -> serviceUnavailableError(node, rng);
            default -> illegalStateError(node, rng);
        };
    }

    private ErrorTemplate timeoutError(ServiceNode node, DeterministicRandom rng) {
        String upstream = rng.nextElement(List.of("payment-gateway", "shipping-api", "notification-service", "fraud-service"));
        int timeoutMs = rng.nextIntBetween(5000, 30000);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("upstream", upstream); f.put("timeoutMs", timeoutMs); f.put("retryCount", rng.nextIntBetween(0, 3));
        String pkg = "com.loggenengine." + toCamel(node.getName());
        return new ErrorTemplate(
                "c.l.e." + capitalize(toCamel(node.getName())) + "Client",
                "http-nio-8080-exec-" + rng.nextIntBetween(1, 20),
                "Connection timeout after " + timeoutMs + "ms calling " + upstream,
                f,
                "java.net.SocketTimeoutException",
                "java.net.SocketTimeoutException: Read timed out\n" +
                "\tat java.net.SocketInputStream.socketRead0(Native Method)\n" +
                "\tat " + pkg + "." + capitalize(toCamel(node.getName())) + "Client.call(" + capitalize(toCamel(node.getName())) + "Client.java:" + rng.nextIntBetween(50, 200) + ")\n" +
                "\tat " + pkg + ".service." + capitalize(toCamel(node.getName())) + "Service.process(Service.java:" + rng.nextIntBetween(80, 300) + ")\n" +
                "\t... " + rng.nextIntBetween(15, 40) + " more"
        );
    }

    private ErrorTemplate nullPointerError(ServiceNode node, DeterministicRandom rng) {
        String pkg = "com.loggenengine." + toCamel(node.getName());
        String className = capitalize(toCamel(node.getName())) + "Service";
        int line = rng.nextIntBetween(50, 300);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("method", "processRequest"); f.put("errorCode", "NPE_" + rng.nextIntBetween(1, 50));
        return new ErrorTemplate(
                "c.l.e." + className,
                "http-nio-8080-exec-" + rng.nextIntBetween(1, 20),
                "Unexpected NullPointerException in " + className + ".processRequest",
                f,
                "java.lang.NullPointerException",
                "java.lang.NullPointerException: Cannot invoke method on null reference\n" +
                "\tat " + pkg + "." + className + ".processRequest(" + className + ".java:" + line + ")\n" +
                "\tat " + pkg + ".controller." + capitalize(toCamel(node.getName())) + "Controller.handle(Controller.java:" + rng.nextIntBetween(30, 150) + ")\n" +
                "\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)\n" +
                "\t... " + rng.nextIntBetween(20, 50) + " more"
        );
    }

    private ErrorTemplate dbConnectionError(ServiceNode node, DeterministicRandom rng) {
        int poolSize = rng.nextIntBetween(10, 50);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("poolSize", poolSize); f.put("activeConnections", poolSize);
        f.put("waitQueueSize", rng.nextIntBetween(5, 100));
        return new ErrorTemplate(
                "c.l.e.DataSourcePool",
                "db-pool-monitor",
                "Database connection pool exhausted: " + poolSize + "/" + poolSize + " connections in use",
                f,
                "org.springframework.jdbc.CannotGetJdbcConnectionException",
                "org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection\n" +
                "\tat org.springframework.jdbc.datasource.DataSourceUtils.getConnection(DataSourceUtils.java:84)\n" +
                "\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:213)\n" +
                "\t... " + rng.nextIntBetween(10, 30) + " more"
        );
    }

    private ErrorTemplate validationError(ServiceNode node, DeterministicRandom rng) {
        String field = rng.nextElement(List.of("orderId", "customerId", "amount", "productId", "quantity"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field); f.put("constraint", "NotNull");
        f.put("rejectedValue", "null");
        return new ErrorTemplate(
                "c.l.e.ValidationHandler",
                "http-nio-8080-exec-" + rng.nextIntBetween(1, 20),
                "Validation failed for field '" + field + "': must not be null",
                f,
                "javax.validation.ConstraintViolationException",
                "javax.validation.ConstraintViolationException: " + field + ": must not be null\n" +
                "\tat org.springframework.validation.beanvalidation.MethodValidationInterceptor.invoke(MethodValidationInterceptor.java:127)\n" +
                "\t... " + rng.nextIntBetween(8, 25) + " more"
        );
    }

    private ErrorTemplate outOfMemoryError(ServiceNode node, DeterministicRandom rng) {
        int usedMb = rng.nextIntBetween(900, 1024);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("heapUsedMb", usedMb); f.put("heapMaxMb", 1024);
        f.put("gcOverheadPercent", rng.nextIntBetween(95, 100));
        return new ErrorTemplate(
                "c.l.e.HeapMonitor",
                "GC-worker-1",
                "JVM heap critically low: " + usedMb + "MB / 1024MB",
                f,
                "java.lang.OutOfMemoryError",
                "java.lang.OutOfMemoryError: Java heap space\n" +
                "\tat java.util.Arrays.copyOf(Arrays.java:3236)\n" +
                "\tat java.util.ArrayList.grow(ArrayList.java:265)\n" +
                "\t... " + rng.nextIntBetween(5, 20) + " more"
        );
    }

    private ErrorTemplate serviceUnavailableError(ServiceNode node, DeterministicRandom rng) {
        String downstream = rng.nextElement(List.of("order-service", "payment-service", "inventory-service", "user-service"));
        int retries = rng.nextIntBetween(1, 5);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("downstream", downstream); f.put("retries", retries);
        f.put("circuitBreaker", "OPEN");
        return new ErrorTemplate(
                "c.l.e.CircuitBreaker",
                "circuit-breaker-monitor",
                "Circuit breaker OPEN for " + downstream + " after " + retries + " failed attempts",
                f,
                "io.github.resilience4j.circuitbreaker.CallNotPermittedException",
                "io.github.resilience4j.circuitbreaker.CallNotPermittedException: CircuitBreaker '" + downstream + "' is OPEN\n" +
                "\tat io.github.resilience4j.circuitbreaker.CircuitBreaker.executeSupplier(CircuitBreaker.java:124)\n" +
                "\t... " + rng.nextIntBetween(5, 15) + " more"
        );
    }

    private ErrorTemplate illegalStateError(ServiceNode node, DeterministicRandom rng) {
        String state = rng.nextElement(List.of("CANCELLED", "EXPIRED", "LOCKED", "PROCESSING"));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("currentState", state); f.put("expectedState", "PENDING");
        return new ErrorTemplate(
                "c.l.e." + capitalize(toCamel(node.getName())) + "StateMachine",
                "state-machine-executor",
                "Invalid state transition: entity is in state " + state + ", expected PENDING",
                f,
                "java.lang.IllegalStateException",
                "java.lang.IllegalStateException: Invalid state transition\n" +
                "\tat com.loggenengine.statemachine.StateMachine.transition(StateMachine.java:" + rng.nextIntBetween(80, 200) + ")\n" +
                "\t... " + rng.nextIntBetween(10, 25) + " more"
        );
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

    private record ErrorTemplate(String logger, String thread, String message,
                                 Map<String, Object> fields,
                                 String exceptionClass, String stackTrace) {}
}
