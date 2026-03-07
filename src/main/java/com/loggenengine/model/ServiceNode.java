package com.loggenengine.model;

import lombok.Builder;
import lombok.Data;

/**
 * A single running instance of a simulated service.
 * e.g., "order-service" instance 2 → instanceId = "order-service-2"
 */
@Data
@Builder
public class ServiceNode {
    private String name;          // e.g., "order-service"
    private String instanceId;    // e.g., "order-service-2"
    private String type;          // gateway | application | database | cache
    private double baseRps;       // requests per second at 100% traffic
    private double errorRate;     // fraction of events that are errors (0.0–1.0)
    private double slowQueryRate; // fraction of DB queries that are slow (0.0–1.0)
    private int instanceIndex;    // 1-based index within service
}
