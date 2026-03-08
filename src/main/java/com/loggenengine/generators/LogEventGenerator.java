package com.loggenengine.generators;

import com.loggenengine.core.DeterministicRandom;
import com.loggenengine.model.EventType;
import com.loggenengine.model.LogEvent;
import com.loggenengine.model.ServiceNode;
import com.loggenengine.model.TraceContext;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates one or more LogEvents for a given service tick.
 */
public interface LogEventGenerator {

    EventType getEventType();

    /**
     * Generate log events for a single occurrence on the given service node.
     * May return multiple events (e.g., an error event + a downstream DB retry).
     */
    List<LogEvent> generate(ServiceNode node,
                            TraceContext trace,
                            Instant timestamp,
                            DeterministicRandom rng,
                            AtomicLong sequenceCounter);

    /**
     * Generate a completion log event after all downstream calls for this service have returned.
     * Only meaningful for orchestrating services (e.g. order-service) that coordinate
     * multiple downstream hops. Terminal services return an empty list by default.
     */
    default List<LogEvent> generateCompletion(ServiceNode node,
                                              TraceContext trace,
                                              Instant timestamp,
                                              DeterministicRandom rng,
                                              AtomicLong sequenceCounter) {
        return List.of();
    }
}
