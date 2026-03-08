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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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

    /** Probability that an app service propagates to each of its configured downstream services. */
    private static final double APP_TO_APP_CALL_PROB = 0.55;

    /**
     * Maximum app-tier call depth before stopping propagation.
     * depth=1: gateway → app (e.g. order-service)
     * depth=2: app → app (e.g. order-service → payment-service)
     * DB calls are always allowed regardless of depth.
     */
    private static final int MAX_APP_DEPTH = 2;

    /** Populated at run() start: service name → nodes for that service. */
    private Map<String, List<ServiceNode>> nodesByService;

    /** Populated at run() start: service name → list of downstream service names it calls. */
    private Map<String, List<String>> callGraph;

    public void run() throws Exception {
        long seed = props.getSeed();
        DeterministicRandom rng = new DeterministicRandom(seed);
        VirtualClock clock = new VirtualClock(props.getVirtualStartTime(), props.getTickSizeSeconds());

        List<ServiceNode> nodes = buildServiceNodes();
        List<ServiceNode> appNodes = nodes.stream()
                .filter(n -> n.getType().equals("application")).toList();
        List<ServiceNode> dbNodes  = nodes.stream()
                .filter(n -> n.getType().equals("database")).toList();

        // Index nodes by service name for O(1) lookup during app-to-app propagation
        nodesByService = nodes.stream().collect(Collectors.groupingBy(ServiceNode::getName));

        // Build call graph from config: service name → downstream service names
        callGraph = props.getServices().stream()
                .filter(sc -> sc.getCalls() != null && !sc.getCalls().isEmpty())
                .collect(Collectors.toMap(
                        SimulationProperties.ServiceConfig::getName,
                        SimulationProperties.ServiceConfig::getCalls));

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
     *
     * <p>Gateway nodes fan out to 1–3 random application services (child spans).
     * Each application service may further propagate to its configured downstream
     * services (app-to-app), and any app service may call the DB.
     *
     * <p>This produces realistic multi-hop traces such as:
     * {@code api-gateway → order-service → payment-service → postgres-main}
     */
    private List<LogEvent> generateEvents(ServiceNode node, TraceContext rootTrace,
                                          Instant now, DeterministicRandom rng,
                                          AtomicLong seqCounter,
                                          List<ServiceNode> appNodes,
                                          List<ServiceNode> dbNodes) {

        List<LogEvent> events = new ArrayList<>();
        boolean isError = rng.nextBoolean(node.getErrorRate());

        // Primary event on this node
        LogEventGenerator primaryGen = pickPrimaryGenerator(node, isError, rng);
        events.addAll(primaryGen.generate(node, rootTrace, now, rng, seqCounter));

        // Gateway → downstream app propagation
        if (node.getType().equals("gateway") && rng.nextBoolean(TRACE_PROPAGATION_PROB)
                && !appNodes.isEmpty()) {

            int downstreamCount = rng.nextIntBetween(1, Math.min(3, appNodes.size()));
            for (int d = 0; d < downstreamCount; d++) {
                ServiceNode downstream = rng.nextElement(appNodes);
                String childSpanId = rng.nextHex(8);
                TraceContext childTrace = rootTrace.childSpan(childSpanId);
                events.addAll(generateAppServiceEvents(downstream, childTrace, now, rng,
                        seqCounter, dbNodes, 1));
            }
        }

        // Database standalone events: attach a synthetic app-caller context note
        if (node.getType().equals("database") && rng.nextBoolean(DB_CALL_PROB)
                && !appNodes.isEmpty()) {

            ServiceNode caller = rng.nextElement(appNodes);
            String callerSpanId = rng.nextHex(8);
            TraceContext callerTrace = rootTrace.childSpan(callerSpanId);
            events.addAll(appGen.generate(caller, callerTrace, now, rng, seqCounter));
        }

        return events;
    }

    /**
     * Generate log events for an application-tier service at the given call depth,
     * including any configured app-to-app downstream calls and a probabilistic DB call.
     *
     * <p>Call depth controls how far the trace propagates:
     * <ul>
     *   <li>depth=1 — directly called by the gateway (e.g. order-service)</li>
     *   <li>depth=2 — called by another app service (e.g. payment-service)</li>
     * </ul>
     * App-to-app propagation stops at {@link #MAX_APP_DEPTH} to prevent runaway chains.
     * DB calls are always allowed at any depth.
     *
     * @param node        the application service node to generate events for
     * @param trace       the parent {@link TraceContext} (traceId is inherited; new spanId assigned)
     * @param depth       current hop depth within the app tier (starts at 1 from gateway)
     */
    private List<LogEvent> generateAppServiceEvents(ServiceNode node, TraceContext trace,
                                                    Instant now, DeterministicRandom rng,
                                                    AtomicLong seqCounter,
                                                    List<ServiceNode> dbNodes,
                                                    int depth) {
        List<LogEvent> events = new ArrayList<>();

        // Generate the primary event for this service
        boolean isError = rng.nextBoolean(node.getErrorRate());
        LogEventGenerator gen = pickPrimaryGenerator(node, isError, rng);
        events.addAll(gen.generate(node, trace, now, rng, seqCounter));

        // App → App: propagate to each configured downstream service (within depth limit)
        if (depth < MAX_APP_DEPTH) {
            List<String> downstream = callGraph.getOrDefault(node.getName(), List.of());
            for (String calledService : downstream) {
                if (rng.nextBoolean(APP_TO_APP_CALL_PROB)) {
                    List<ServiceNode> calledNodes = nodesByService.getOrDefault(calledService, List.of());
                    if (!calledNodes.isEmpty()) {
                        ServiceNode calledNode = rng.nextElement(calledNodes);
                        String childSpanId = rng.nextHex(8);
                        TraceContext childTrace = trace.childSpan(childSpanId);
                        events.addAll(generateAppServiceEvents(calledNode, childTrace, now, rng,
                                seqCounter, dbNodes, depth + 1));
                    }
                }
            }
        }

        // App → DB: probabilistic database call (always allowed, regardless of depth)
        if (rng.nextBoolean(DB_CALL_PROB) && !dbNodes.isEmpty()) {
            ServiceNode dbNode = rng.nextElement(dbNodes);
            String dbSpanId = rng.nextHex(8);
            TraceContext dbTrace = trace.childSpan(dbSpanId);
            events.addAll(dbGen.generate(dbNode, dbTrace, now, rng, seqCounter));
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
