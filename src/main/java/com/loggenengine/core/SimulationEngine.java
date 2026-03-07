package com.loggenengine.core;

import com.loggenengine.config.SimulationProperties;
import com.loggenengine.generators.*;
import com.loggenengine.model.*;
import com.loggenengine.output.CompositeOutputAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The core simulation loop.
 *
 * <p>For every virtual second (tick):
 * <ol>
 *   <li>Calculate the time-of-day traffic multiplier.</li>
 *   <li>For each service node, Poisson-sample the number of events for this tick.</li>
 *   <li>For each event, pick a generator (HTTP, APP, DB, ERROR) based on service type and error rate.</li>
 *   <li>Generate distributed-trace-correlated log events.</li>
 *   <li>Write all events to the composite output adapter.</li>
 *   <li>Advance the virtual clock by one tick.</li>
 * </ol>
 * Everything is single-threaded to guarantee determinism.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulationEngine {

    private final SimulationProperties        props;
    private final CompositeOutputAdapter      output;
    private final HttpRequestLogGenerator     httpGen;
    private final ApplicationLogGenerator     appGen;
    private final ErrorLogGenerator           errorGen;
    private final DatabaseLogGenerator        dbGen;

    /** Probability that a gateway HTTP event triggers downstream service calls. */
    private static final double TRACE_PROPAGATION_PROB = 0.60;

    /** Probability that an application event triggers a DB query. */
    private static final double DB_CALL_PROB = 0.45;

    public void run() throws Exception {
        long seed = props.getSeed();
        DeterministicRandom rng = new DeterministicRandom(seed);
        VirtualClock clock = new VirtualClock(props.getVirtualStartTime(), props.getTickSizeSeconds());

        List<ServiceNode> nodes = buildServiceNodes();
        List<ServiceNode> appNodes = nodes.stream()
                .filter(n -> n.getType().equals("application")).toList();
        List<ServiceNode> dbNodes  = nodes.stream()
                .filter(n -> n.getType().equals("database")).toList();

        AtomicLong seqCounter = new AtomicLong(1);

        long totalTicks = (long) props.getDurationHours() * 3600L / props.getTickSizeSeconds();

        log.info("=== Deterministic Log Simulation Engine ===");
        log.info("Seed: {}  |  Start: {}  |  Duration: {}h  |  Ticks: {}",
                seed, props.getVirtualStartTime(), props.getDurationHours(), totalTicks);
        log.info("Services: {}  |  Nodes: {}",
                props.getServices().size(), nodes.size());

        output.open();

        long loggedAt = 0;
        for (long tick = 0; tick < totalTicks; tick++) {
            double todMul = clock.todMultiplier();
            Instant now   = clock.now();

            for (ServiceNode node : nodes) {
                double effectiveLambda = node.getBaseRps() * props.getTickSizeSeconds() * todMul;
                int eventCount = rng.poissonSample(effectiveLambda);

                for (int i = 0; i < eventCount; i++) {
                    // Generate a root trace for this event
                    String traceId = rng.nextHex(16);
                    String spanId  = rng.nextHex(8);
                    TraceContext rootTrace = TraceContext.root(traceId, spanId);

                    List<LogEvent> events = generateEvents(node, rootTrace, now, rng, seqCounter,
                            appNodes, dbNodes);

                    for (LogEvent e : events) {
                        output.write(e);
                    }
                }
            }

            // Progress log every simulated hour
            if (tick % 3600 == 0 && tick > 0) {
                long hour = tick / 3600;
                log.info("Progress: {}/{}h simulated | seq={} | virtual={}",
                        hour, props.getDurationHours(), seqCounter.get(), now);
            }

            clock.advance();
        }

        output.close();

        log.info("=== Simulation complete ===");
        log.info("Total events generated: {}", seqCounter.get() - 1);
        log.info("Output: text={} | json={}",
                props.getOutput().getFile().getTextPath(),
                props.getOutput().getFile().getJsonPath());
    }

    /**
     * Generate all log events for one occurrence on a service node.
     * May include downstream service + DB events sharing the same traceId.
     */
    private List<LogEvent> generateEvents(ServiceNode node, TraceContext rootTrace,
                                          Instant now, DeterministicRandom rng,
                                          AtomicLong seqCounter,
                                          List<ServiceNode> appNodes,
                                          List<ServiceNode> dbNodes) {

        List<LogEvent> events = new ArrayList<>();
        boolean isError = rng.nextBoolean(node.getErrorRate());

        // Primary event
        LogEventGenerator primaryGen = pickPrimaryGenerator(node, isError, rng);
        events.addAll(primaryGen.generate(node, rootTrace, now, rng, seqCounter));

        // Trace propagation: gateway events trigger downstream application calls
        if (node.getType().equals("gateway") && rng.nextBoolean(TRACE_PROPAGATION_PROB)
                && !appNodes.isEmpty()) {

            int downstreamCount = rng.nextIntBetween(1, Math.min(3, appNodes.size()));
            for (int d = 0; d < downstreamCount; d++) {
                ServiceNode downstream = rng.nextElement(appNodes);
                String childSpanId = rng.nextHex(8);
                TraceContext childTrace = rootTrace.childSpan(childSpanId);

                boolean downstreamError = rng.nextBoolean(downstream.getErrorRate());
                LogEventGenerator dGen = pickPrimaryGenerator(downstream, downstreamError, rng);
                events.addAll(dGen.generate(downstream, childTrace, now, rng, seqCounter));

                // Application services often call the DB
                if (rng.nextBoolean(DB_CALL_PROB) && !dbNodes.isEmpty()) {
                    ServiceNode dbNode = rng.nextElement(dbNodes);
                    String dbSpanId = rng.nextHex(8);
                    TraceContext dbTrace = childTrace.childSpan(dbSpanId);
                    events.addAll(dbGen.generate(dbNode, dbTrace, now, rng, seqCounter));
                }
            }
        }

        // Database nodes: some events also generate a parent application context note
        if (node.getType().equals("database") && rng.nextBoolean(DB_CALL_PROB)
                && !appNodes.isEmpty()) {

            ServiceNode caller = rng.nextElement(appNodes);
            String callerSpanId = rng.nextHex(8);
            TraceContext callerTrace = rootTrace.childSpan(callerSpanId);
            events.addAll(appGen.generate(caller, callerTrace, now, rng, seqCounter));
        }

        return events;
    }

    /** Choose which generator to use for this service tick. */
    private LogEventGenerator pickPrimaryGenerator(ServiceNode node, boolean isError,
                                                   DeterministicRandom rng) {
        if (isError) return errorGen;

        return switch (node.getType()) {
            case "gateway"     -> httpGen;
            case "database"    -> dbGen;
            case "cache"       -> appGen;   // cache service uses app-style events
            default -> {                    // application
                // Mix: 60% app events, 40% HTTP (intra-service REST calls)
                yield rng.nextBoolean(0.60) ? appGen : httpGen;
            }
        };
    }

    /** Build the flat list of ServiceNode instances from config. */
    private List<ServiceNode> buildServiceNodes() {
        List<ServiceNode> nodes = new ArrayList<>();
        for (SimulationProperties.ServiceConfig sc : props.getServices()) {
            for (int i = 1; i <= sc.getInstances(); i++) {
                nodes.add(ServiceNode.builder()
                        .name(sc.getName())
                        .instanceId(sc.getName() + "-" + i)
                        .type(sc.getType())
                        .baseRps(sc.getBaseRps())
                        .errorRate(sc.getErrorRate())
                        .slowQueryRate(sc.getSlowQueryRate())
                        .instanceIndex(i)
                        .build());
            }
        }
        return nodes;
    }
}
