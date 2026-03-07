package com.loggenengine.output;

import com.loggenengine.config.SimulationProperties;
import com.loggenengine.formatter.JsonLogFormatter;
import com.loggenengine.model.LogEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;

/**
 * Publishes log events to a Kafka topic as JSON.
 * This adapter is NOT a Spring bean — it is instantiated manually by
 * {@link com.loggenengine.config.OutputAdapterConfig} only when Kafka is enabled.
 *
 * <p>All producer settings are read from {@link SimulationProperties.KafkaOutputConfig}
 * so they can be tuned in {@code application.yml} without recompiling.
 */
@Slf4j
public class KafkaOutputAdapter implements OutputAdapter {

    private final JsonLogFormatter formatter;
    private final SimulationProperties.KafkaOutputConfig kafkaConfig;

    private KafkaProducer<String, String> producer;

    public KafkaOutputAdapter(JsonLogFormatter formatter,
                              SimulationProperties.KafkaOutputConfig kafkaConfig) {
        this.formatter = formatter;
        this.kafkaConfig = kafkaConfig;
    }

    @Override
    public void open() {
        SimulationProperties.ProducerConfig pc = kafkaConfig.getProducer();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      kafkaConfig.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   pc.getKeySerializer());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, pc.getValueSerializer());
        props.put(ProducerConfig.ACKS_CONFIG,                   pc.getAcks());
        props.put(ProducerConfig.LINGER_MS_CONFIG,              String.valueOf(pc.getLingerMs()));
        props.put(ProducerConfig.BATCH_SIZE_CONFIG,             String.valueOf(pc.getBatchSize()));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG,       pc.getCompressionType());

        producer = new KafkaProducer<>(props);
        log.info("Kafka output opened: servers={}, topic={}, acks={}, compression={}",
                kafkaConfig.getBootstrapServers(), kafkaConfig.getTopic(),
                pc.getAcks(), pc.getCompressionType());
    }

    @Override
    public void write(LogEvent event) {
        String json = formatter.format(event);
        // Key = traceId so events from the same trace go to the same partition
        ProducerRecord<String, String> record =
                new ProducerRecord<>(kafkaConfig.getTopic(), event.getTraceId(), json);
        producer.send(record);
    }

    @Override
    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
            log.info("Kafka output closed.");
        }
    }
}
