# logGenEngine — Deterministic Log Simulation Engine

A Spring Boot engine that generates realistic, production-style log streams
deterministically from a seed.  The same seed and configuration always produces
byte-for-byte identical output, making the engine a reliable foundation for
building and benchmarking AI log-intelligence systems
(anomaly detection, root-cause analysis, log parsing, trace correlation).

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Project Structure](#project-structure)
4. [Simulated Services](#simulated-services)
5. [Output Formats](#output-formats)
6. [Distributed Trace Propagation](#distributed-trace-propagation)
7. [Configuration Reference](#configuration-reference)
8. [Building and Running](#building-and-running)
9. [Verifying Determinism](#verifying-determinism)
10. [Enabling Kafka Output](#enabling-kafka-output)
11. [Design Decisions](#design-decisions)

---

## Overview

### What it does

The engine replays a configurable e-commerce microservice topology through
virtual time, emitting log events at realistic rates and patterns.
Traffic volume follows a time-of-day curve (busy mornings, quiet nights).
HTTP requests propagate distributed trace IDs across service boundaries, so
the output contains the correlated, multi-service log entries that real
observability tools must handle.

### Key properties

| Property | Value |
|---|---|
| Default seed | `42` |
| Default virtual window | 24 hours |
| Events generated (24 h) | ~47.7 million |
| Wall-clock run time (24 h) | ~50 seconds |
| Text output size (24 h) | ~11 GB |
| JSON Lines output size (24 h) | ~19 GB |
| Determinism | Bit-for-bit identical across runs |

---

## Architecture

```
application.yml
      │
      ▼
SimulationProperties  ──────────────────────────────────────────┐
      │                                                          │
      ▼                                                          │
SimulationEngine  (single-threaded loop, 86 400 ticks for 24 h) │
      │                                                          │
      ├── DeterministicRandom  (seeded java.util.Random)         │
      ├── VirtualClock         (virtual time + ToD multiplier)   │
      │                                                          │
      │  For each tick, for each ServiceNode:                    │
      │    poissonSample(baseRps × tickSize × todMultiplier)     │
      │      │                                                   │
      │      ├── HttpRequestLogGenerator                         │
      │      ├── ApplicationLogGenerator                         │
      │      ├── DatabaseLogGenerator                            │
      │      └── ErrorLogGenerator                               │
      │                                                          │
      ▼                                                          │
CompositeOutputAdapter ◄────────────────────────────────────────┘
      │
      ├── FileOutputAdapter   →  simulation.log  (text)
      │                      →  simulation.jsonl (JSON Lines)
      ├── StdoutOutputAdapter →  System.out      (text)
      └── KafkaOutputAdapter  →  Kafka topic     (JSON, optional)
```

### Determinism guarantee

A single `java.util.Random` instance is seeded once from `simulation.seed`.
The engine loop is single-threaded and processes services in a fixed order,
so the RNG is always advanced in the same sequence.
Changing *any* configuration value (including adding a new service) will
produce a different output, but re-running with the exact same config is
always reproducible.

### Time-of-day traffic shaping

The effective event rate each tick is:

```
effectiveLambda = baseRps × tickSizeSeconds × todMultiplier(virtualHour)
```

`todMultiplier` is a sum of Gaussians centred on three traffic peaks:

| Peak | Hour | Multiplier |
|---|---|---|
| Morning rush | 09:00 | ~0.9× |
| Lunch | 12:00 | ~0.7× |
| Evening | 18:00 | ~0.6× |
| Quiet night | 03:00 | ~0.1× |

---

## Project Structure

```
src/main/
├── java/com/loggenengine/
│   ├── LogGenEngineApplication.java   Entry point — @SpringBootApplication + CommandLineRunner
│   ├── config/
│   │   ├── SimulationProperties.java  @ConfigurationProperties("simulation") — all config
│   │   └── OutputAdapterConfig.java   Wires active OutputAdapters into a CompositeOutputAdapter
│   ├── core/
│   │   ├── DeterministicRandom.java   Seeded RNG wrapper (Poisson, nextHex, nextElement, …)
│   │   ├── VirtualClock.java          Virtual time management + time-of-day multiplier
│   │   └── SimulationEngine.java      Main simulation loop — orchestrates all components
│   ├── model/
│   │   ├── LogEvent.java              Core event record (@Builder)
│   │   ├── LogLevel.java              TRACE | DEBUG | INFO | WARN | ERROR
│   │   ├── EventType.java             HTTP_REQUEST | APPLICATION | DATABASE | ERROR | SYSTEM
│   │   ├── TraceContext.java          traceId / spanId / parentSpanId (Java record)
│   │   └── ServiceNode.java           Single running instance of a simulated service
│   ├── generators/
│   │   ├── LogEventGenerator.java     Interface
│   │   ├── HttpRequestLogGenerator.java   HTTP access logs (method, path, status, latency)
│   │   ├── ApplicationLogGenerator.java   Business events per service (orders, payments, …)
│   │   ├── ErrorLogGenerator.java         ERROR events with realistic stack traces
│   │   └── DatabaseLogGenerator.java      SQL query logs, slow-query detection
│   ├── formatter/
│   │   ├── LogFormatter.java          Interface
│   │   ├── TextLogFormatter.java      Logback-style text
│   │   └── JsonLogFormatter.java      Jackson-based JSON Lines
│   └── output/
│       ├── OutputAdapter.java         Interface (open / write / close)
│       ├── CompositeOutputAdapter.java Fan-out to all active adapters
│       ├── StdoutOutputAdapter.java   Console output (text)
│       ├── FileOutputAdapter.java     Dual-file output (text + JSON Lines)
│       └── KafkaOutputAdapter.java    Kafka producer (created manually, not a Spring bean)
└── resources/
    └── application.yml                All configuration — single source of truth
```

---

## Simulated Services

The default scenario models an e-commerce platform with seven services and
thirteen nodes in total.

| Service | Type | Instances | Base RPS | Error Rate |
|---|---|---|---|---|
| `api-gateway` | gateway | 2 | 100 | 2 % |
| `order-service` | application | 3 | 30 | 5 % |
| `payment-service` | application | 2 | 10 | 3 % |
| `inventory-service` | application | 2 | 25 | 2 % |
| `user-service` | application | 2 | 20 | 1 % |
| `postgres-main` | database | 1 | 150 | 0.5 % |
| `redis-cache` | cache | 1 | 300 | 0.1 % |

Add, remove, or tune services by editing `application.yml` — no code changes needed.

---

## Output Formats

### Text — Logback style (`simulation.log`)

```
2024-01-15 09:00:15.234 INFO  [order-service-2] [trace=f8a2b3c4d5e6f7a8,span=a1b2c3d4] c.l.e.OrderService - Processing order ORD-789012 for customer cust-12345 {orderId=ORD-789012, customerId=cust-12345, amount=149.99}
2024-01-15 09:00:15.456 ERROR [payment-service-1] [trace=f8a2b3c4d5e6f7a8,span=b2c3d4e5] c.l.e.PaymentProcessor - Connection timeout after 30000ms calling payment-gateway {upstream=payment-gateway, timeoutMs=30000, retryCount=2}
java.net.SocketTimeoutException: Read timed out
    at java.net.SocketInputStream.socketRead0(Native Method)
    at com.loggenengine.PaymentGatewayClient.call(PaymentGatewayClient.java:87)
    ... 23 more
```

### JSON Lines — one object per line (`simulation.jsonl`)

```json
{"timestamp":"2024-01-15T09:00:15.234Z","seq":1234,"level":"INFO","eventType":"APPLICATION","service":"order-service","instanceId":"order-service-2","traceId":"f8a2b3c4d5e6f7a8","spanId":"a1b2c3d4","parentSpanId":"cc0553d0","logger":"c.l.e.OrderService","thread":"http-nio-8080-exec-3","message":"Processing order ORD-789012 for customer cust-12345","fields":{"orderId":"ORD-789012","customerId":"cust-12345","amount":149.99}}
```

Every field of `LogEvent` is included:

| Field | Description |
|---|---|
| `timestamp` | ISO-8601 virtual timestamp |
| `seq` | Global monotonic sequence number |
| `level` | TRACE / DEBUG / INFO / WARN / ERROR |
| `eventType` | HTTP_REQUEST / APPLICATION / DATABASE / ERROR / SYSTEM |
| `service` | Service name (e.g. `order-service`) |
| `instanceId` | Instance ID (e.g. `order-service-2`) |
| `traceId` | 16-char hex — shared across correlated events |
| `spanId` | 8-char hex — unique per event |
| `parentSpanId` | 8-char hex — `null` for root spans |
| `logger` | Abbreviated logger class name |
| `thread` | Thread name |
| `message` | Human-readable log message |
| `fields` | Structured context (method, statusCode, durationMs, …) |
| `exceptionClass` | Exception class — ERROR events only |
| `stackTrace` | Abbreviated stack trace — ERROR events only |

---

## Distributed Trace Propagation

Every API gateway request generates a root `TraceContext` with a new `traceId`
and `spanId`.  With 60 % probability the engine also emits downstream events
for 1–3 application services, each receiving a child span that references the
gateway's `spanId` as its `parentSpanId`.  Application services further
propagate the trace into database queries with their own child spans.

```
api-gateway-1       traceId=f8a2b3c4  spanId=cc0553d0  parent=ROOT
  └─ order-service-2  traceId=f8a2b3c4  spanId=a1b2c3d4  parent=cc0553d0
       └─ postgres-main-1  traceId=f8a2b3c4  spanId=fd77860a  parent=a1b2c3d4
```

This structure lets any log-intelligence tool reconstruct the full call graph
from the log stream alone.

---

## Configuration Reference

All properties live under the `simulation:` key in `application.yml`.

### Top-level

| Property | Type | Description |
|---|---|---|
| `seed` | `long` | Master random seed. Same seed → identical output. |
| `virtual-start-time` | ISO-8601 | Virtual clock start timestamp. |
| `duration-hours` | `int` | Length of the simulated window in hours. |
| `tick-size-seconds` | `long` | Virtual seconds per engine tick. |

### `services[]`

| Property | Type | Description |
|---|---|---|
| `name` | `string` | Service identifier, e.g. `order-service`. |
| `type` | `string` | `gateway` \| `application` \| `database` \| `cache`. |
| `instances` | `int` | Number of independent nodes to simulate. |
| `base-rps` | `double` | Baseline events/second at 100 % traffic load. |
| `error-rate` | `double` | Fraction of events emitted as errors (0.0–1.0). |
| `slow-query-rate` | `double` | Fraction of DB queries flagged as slow (database type only). |

### `output`

| Property | Type | Description |
|---|---|---|
| `stdout` | `boolean` | Print events to standard output. |
| `file.enabled` | `boolean` | Write text + JSON Lines files. |
| `file.text-path` | `string` | Path for the Logback-style `.log` file. |
| `file.json-path` | `string` | Path for the JSON Lines `.jsonl` file. |
| `kafka.enabled` | `boolean` | Publish events to Kafka. |
| `kafka.bootstrap-servers` | `string` | Kafka broker list (`host:port`). |
| `kafka.topic` | `string` | Target Kafka topic name. |
| `kafka.producer.acks` | `string` | Acknowledgement level: `0`, `1`, or `all`. |
| `kafka.producer.linger-ms` | `int` | Batch wait time in milliseconds. |
| `kafka.producer.batch-size` | `int` | Maximum batch size in bytes. |
| `kafka.producer.compression-type` | `string` | `none` \| `gzip` \| `snappy` \| `lz4` \| `zstd`. |
| `kafka.producer.key-serializer` | `string` | Fully-qualified key serializer class. |
| `kafka.producer.value-serializer` | `string` | Fully-qualified value serializer class. |

---

## Building and Running

### Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 (Microsoft build recommended) |
| Maven | 3.9+ |

> **Important:** The system Maven on this machine defaults to JDK 25 (Homebrew),
> which is incompatible with Lombok 1.18.30.  Always set `JAVA_HOME` to JDK 21
> when building or running.

### Build

```bash
JAVA_HOME=/Users/ramana/Library/Java/JavaVirtualMachines/ms-21.0.7/Contents/Home \
  mvn clean package -q
```

### Run (default — 24-hour simulation, seed 42)

```bash
JAVA_HOME=/Users/ramana/Library/Java/JavaVirtualMachines/ms-21.0.7/Contents/Home \
  java -jar target/logGenEngine-1.0.0-SNAPSHOT.jar
```

### Run a short development simulation (1 hour)

Override `duration-hours` on the command line without editing `application.yml`:

```bash
JAVA_HOME=/Users/ramana/Library/Java/JavaVirtualMachines/ms-21.0.7/Contents/Home \
  java -jar target/logGenEngine-1.0.0-SNAPSHOT.jar \
       --simulation.duration-hours=1
```

### Override seed

```bash
java -jar target/logGenEngine-1.0.0-SNAPSHOT.jar \
     --simulation.seed=999
```

---

## Verifying Determinism

```bash
# First run
java -jar target/logGenEngine-1.0.0-SNAPSHOT.jar
cp output/simulation.log output/simulation.log.bak

# Second run (same seed)
java -jar target/logGenEngine-1.0.0-SNAPSHOT.jar

# Compare — should print nothing (zero differences)
diff output/simulation.log output/simulation.log.bak && echo "DETERMINISM OK"
```

Verify JSON Lines are valid:

```bash
head -20 output/simulation.jsonl | python3 -m json.tool > /dev/null && echo "JSON OK"
```

Inspect a distributed trace:

```bash
TRACE=$(head -1 output/simulation.jsonl | python3 -c "import sys,json; print(json.load(sys.stdin)['traceId'])")
grep "\"traceId\":\"$TRACE\"" output/simulation.jsonl \
  | python3 -c "import sys,json; [print(e['instanceId'], e['spanId'], e['message'][:60]) for e in (json.loads(l) for l in sys.stdin)]"
```

---

## Enabling Kafka Output

1. Start a local Kafka broker (e.g. via Docker):

```bash
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_KRAFT_MODE=true \
  apache/kafka:latest
```

2. Create the topic:

```bash
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --create --topic simulation-logs \
  --bootstrap-server localhost:9092 \
  --partitions 6 --replication-factor 1
```

3. Enable Kafka in `application.yml`:

```yaml
simulation:
  output:
    kafka:
      enabled: true
      bootstrap-servers: localhost:9092
      topic: simulation-logs
```

4. Run the engine — events will be published to the topic in real time.

---

## Design Decisions

| Decision | Rationale |
|---|---|
| Single-threaded simulation loop | Guarantees determinism; multi-threading would require synchronised RNG access and make replay order non-deterministic. |
| Knuth Poisson sampling | Exact, deterministic with a seeded RNG; consumes a fixed number of doubles per sample, preserving the RNG stream. |
| `KafkaOutputAdapter` is not a Spring bean | Avoids Spring Kafka auto-configuration attempting a broker connection at startup when Kafka is disabled. The adapter is instantiated manually in `OutputAdapterConfig` only when `kafka.enabled=true`. |
| `@ConfigurationProperties` over `@Value` | Provides typed, structured config with IDE auto-completion and validation; makes all configuration visible in one class. |
| Partition key = `traceId` | Ensures all log events belonging to the same distributed trace land in the same Kafka partition, simplifying consumer-side correlation. |
| No Java-level defaults in `KafkaOutputConfig` | `application.yml` is the single source of truth for all Kafka values; missing properties fail fast rather than silently using hidden defaults. |
