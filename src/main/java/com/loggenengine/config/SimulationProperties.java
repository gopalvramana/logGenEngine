package com.loggenengine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration for the Deterministic Log Simulation Engine.
 *
 * <p>All properties are bound from {@code application.yml} under the
 * {@code simulation} prefix. Changing any value here changes the engine
 * behaviour without recompiling.
 *
 * <p><b>Determinism guarantee:</b> given the same {@link #seed}, the engine
 * always produces byte-for-byte identical output regardless of when or how
 * many times it is run.
 *
 * <p>Example minimal configuration:
 * <pre>{@code
 * simulation:
 *   seed: 42
 *   duration-hours: 1
 * }</pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "simulation")
public class SimulationProperties {

    /**
     * Random seed that drives every stochastic decision in the engine
     * (event counts, log levels, message content, trace IDs, …).
     *
     * <p>Two runs with the same seed always produce identical output.
     * Change the seed to get a completely different — but still
     * reproducible — log stream.
     */
    private long seed = 42;

    /**
     * ISO-8601 timestamp that the virtual clock starts from.
     *
     * <p>All generated log entries carry timestamps starting at this value
     * and advancing by {@link #tickSizeSeconds} per tick. The value has no
     * effect on the volume or ordering of events; it only determines the
     * wall-clock timestamps written into the output files.
     */
    private Instant virtualStartTime = Instant.parse("2024-01-15T00:00:00Z");

    /**
     * Total length of the simulated time window, in virtual hours.
     *
     * <p>The engine runs for {@code durationHours × 3600 / tickSizeSeconds}
     * ticks. A 24-hour run at the default RPS settings produces roughly
     * 47 million log events.
     *
     * <p>Reduce this value during development to shorten run time and
     * output file size.
     */
    private int durationHours = 24;

    /**
     * Number of virtual seconds that elapse per simulation tick.
     *
     * <p>Each tick generates Poisson-distributed events for every service
     * node. Increasing this value reduces tick count and speeds up the run,
     * but coarsens the timestamp resolution of individual log entries.
     * The default of {@code 1} gives one-second granularity.
     */
    private long tickSizeSeconds = 1;

    /**
     * Ordered list of services to include in the simulation.
     *
     * <p>Each entry maps to one or more {@link ServiceNode} instances
     * (controlled by {@link ServiceConfig#instances}). The engine generates
     * events for every node on every tick.
     */
    private List<ServiceConfig> services = List.of();

    /**
     * Output sink configuration — controls where generated log events are
     * written (stdout, file, Kafka, or any combination).
     */
    private OutputConfig output = new OutputConfig();

    // -----------------------------------------------------------------------
    // Nested configuration classes
    // -----------------------------------------------------------------------

    /**
     * Configuration for a single simulated service (e.g. {@code order-service}).
     *
     * <p>The engine expands each {@code ServiceConfig} into
     * {@link #instances} independent {@code ServiceNode} objects, each with
     * its own instance ID (e.g. {@code order-service-1}, {@code order-service-2}).
     */
    @Data
    public static class ServiceConfig {

        /** Logical service name, e.g. {@code order-service}. Used as the
         *  {@code service} field in every emitted log event. */
        private String name;

        /**
         * Service role — determines which log generators are used.
         *
         * <ul>
         *   <li>{@code gateway}     — HTTP access logs, routing decisions</li>
         *   <li>{@code application} — business logic, cache, scheduled jobs</li>
         *   <li>{@code database}    — SQL query logs, slow-query alerts</li>
         *   <li>{@code cache}       — key lookup hit/miss events</li>
         * </ul>
         */
        private String type = "application";

        /**
         * Number of independent instances to simulate for this service.
         *
         * <p>Each instance gets its own instance ID suffix
         * (e.g. {@code payment-service-1}, {@code payment-service-2})
         * and generates events independently.
         */
        private int instances = 1;

        /**
         * Baseline event rate in events per second at 100 % traffic load.
         *
         * <p>The actual event count per tick is Poisson-sampled around
         * {@code baseRps × tickSizeSeconds × todMultiplier}, so the
         * instantaneous rate varies naturally. The time-of-day multiplier
         * peaks at ~0.9 around 9 AM and drops to ~0.1 at 3 AM.
         */
        private double baseRps = 10;

        /**
         * Fraction of events that are generated as ERROR-level entries,
         * expressed as a value between {@code 0.0} (no errors) and
         * {@code 1.0} (all errors).
         *
         * <p>Example: {@code 0.05} means roughly 5 % of events for this
         * service will be errors with stack traces.
         */
        private double errorRate = 0.01;

        /**
         * Fraction of database query events that are flagged as slow queries,
         * expressed as a value between {@code 0.0} and {@code 1.0}.
         *
         * <p>Relevant only for services with {@link #type} = {@code database}.
         * Slow queries are emitted at WARN level and include an
         * {@code explainNeeded=true} field.
         */
        private double slowQueryRate = 0.0;

        /**
         * Downstream service names this service calls during normal request processing.
         * Drives app-to-app trace propagation in the simulation engine.
         * Example: order-service calls [user-service, inventory-service, payment-service].
         */
        private List<String> calls = new ArrayList<>();
    }

    /**
     * Top-level output configuration — governs which sinks are active.
     *
     * <p>Multiple sinks can be enabled simultaneously; the engine fans out
     * every event to all active sinks via {@code CompositeOutputAdapter}.
     */
    @Data
    public static class OutputConfig {

        /**
         * When {@code true}, log events are printed to standard output in
         * Logback text format.
         *
         * <p>Disabled by default because a full 24-hour run produces tens of
         * millions of lines. Enable only for short runs or debugging.
         */
        private boolean stdout = false;

        /** File output settings (text + JSON Lines). */
        private FileOutputConfig file = new FileOutputConfig();

        /** Kafka producer settings. */
        private KafkaOutputConfig kafka = new KafkaOutputConfig();
    }

    /**
     * Configuration for file-based output.
     *
     * <p>Two files are written in parallel for every enabled run:
     * <ul>
     *   <li>A plain-text ({@code .log}) file in Logback pattern format —
     *       human-readable, suitable for {@code grep} and text tools.</li>
     *   <li>A JSON Lines ({@code .jsonl}) file with one JSON object per
     *       line — machine-readable, ideal for log parsers and AI pipelines.</li>
     * </ul>
     */
    @Data
    public static class FileOutputConfig {

        /** Enable or disable file output entirely. */
        private boolean enabled = true;

        /**
         * Absolute or relative path for the Logback-style text log file.
         *
         * <p>The parent directory is created automatically if it does not exist.
         * The file is truncated on each run (not appended).
         */
        private String textPath = "./output/simulation.log";

        /**
         * Absolute or relative path for the JSON Lines output file.
         *
         * <p>Each line is a self-contained JSON object representing one
         * {@code LogEvent}. The file is truncated on each run.
         */
        private String jsonPath = "./output/simulation.jsonl";
    }

    /**
     * Configuration for the Kafka output sink.
     *
     * <p>When {@link #enabled} is {@code true}, the engine creates a raw
     * {@link org.apache.kafka.clients.producer.KafkaProducer} (not managed by
     * Spring) and publishes every log event as a JSON string to {@link #topic}.
     * The partition key is the event's {@code traceId}, ensuring that all
     * correlated log entries from the same distributed trace land in the
     * same Kafka partition.
     *
     * <p>Kafka auto-configuration is excluded from the Spring context
     * ({@code @SpringBootApplication(exclude = KafkaAutoConfiguration.class)})
     * so the application starts cleanly without a Kafka broker when this
     * sink is disabled.
     */
    @Data
    public static class KafkaOutputConfig {

        /**
         * Enable or disable the Kafka output sink.
         *
         * <p>Set to {@code true} only when a Kafka broker is reachable at
         * {@link #bootstrapServers}. The application fails fast at startup
         * if the broker is unreachable and this flag is {@code true}.
         */
        private boolean enabled;

        /**
         * Comma-separated list of Kafka broker addresses in
         * {@code host:port} format, e.g. {@code localhost:9092}.
         *
         * <p>Used as the value of the Kafka producer property
         * {@code bootstrap.servers}.
         */
        private String bootstrapServers;

        /**
         * Name of the Kafka topic to publish log events to.
         *
         * <p>The topic must exist before the engine starts (auto-creation
         * depends on broker configuration). Each log event is published as
         * a single message; the message key is the event's {@code traceId}.
         */
        private String topic;

        /**
         * Kafka producer tuning properties.
         * Must be initialised here so Spring has an object to bind values into.
         */
        private ProducerConfig producer = new ProducerConfig();
    }

    /**
     * Low-level Kafka producer tuning knobs.
     *
     * <p>These map directly to standard Kafka producer client properties.
     * All values are sourced exclusively from {@code application.yml};
     * none have Java-level defaults so misconfiguration is detected early
     * rather than silently falling back to hidden defaults.
     *
     * @see <a href="https://kafka.apache.org/documentation/#producerconfigs">
     *      Kafka Producer Configuration</a>
     */
    @Data
    public static class ProducerConfig {

        /**
         * Number of broker acknowledgements required before a produce request
         * is considered complete ({@code 0}, {@code 1}, or {@code all}).
         *
         * <ul>
         *   <li>{@code "0"}   — fire-and-forget; fastest, no durability guarantee.</li>
         *   <li>{@code "1"}   — leader-ack; good balance of speed and safety.</li>
         *   <li>{@code "all"} — full ISR ack; slowest, strongest durability.</li>
         * </ul>
         *
         * Maps to Kafka producer property {@code acks}.
         */
        private String acks;

        /**
         * Time in milliseconds the producer waits before sending a batch,
         * allowing more records to accumulate.
         *
         * <p>Higher values increase throughput at the cost of latency.
         * Maps to Kafka producer property {@code linger.ms}.
         */
        private int lingerMs;

        /**
         * Maximum size in bytes of a single produce batch per partition.
         *
         * <p>Larger batches improve throughput but increase memory use.
         * Maps to Kafka producer property {@code batch.size}.
         * Default in {@code application.yml}: {@code 65536} (64 KB).
         */
        private int batchSize;

        /**
         * Compression algorithm applied to each batch before sending.
         * Accepted values: {@code none}, {@code gzip}, {@code snappy},
         * {@code lz4}, {@code zstd}.
         *
         * <p>{@code lz4} offers a good trade-off between compression ratio
         * and CPU overhead for log-shaped data.
         * Maps to Kafka producer property {@code compression.type}.
         */
        private String compressionType;

        /**
         * Fully-qualified class name of the Kafka message key serializer.
         *
         * <p>Keys are trace IDs (plain strings), so
         * {@code org.apache.kafka.common.serialization.StringSerializer}
         * is the correct choice.
         * Maps to Kafka producer property {@code key.serializer}.
         */
        private String keySerializer;

        /**
         * Fully-qualified class name of the Kafka message value serializer.
         *
         * <p>Values are JSON strings, so
         * {@code org.apache.kafka.common.serialization.StringSerializer}
         * is the correct choice.
         * Maps to Kafka producer property {@code value.serializer}.
         */
        private String valueSerializer;
    }
}
