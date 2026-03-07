package com.loggenengine.config;

import com.loggenengine.formatter.JsonLogFormatter;
import com.loggenengine.output.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the active {@link OutputAdapter}s based on configuration and
 * exposes a single {@link CompositeOutputAdapter} bean to the engine.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class OutputAdapterConfig {

    private final SimulationProperties props;
    private final StdoutOutputAdapter stdoutAdapter;
    private final FileOutputAdapter   fileAdapter;
    private final JsonLogFormatter    jsonFormatter;

    @Bean
    public CompositeOutputAdapter compositeOutputAdapter() {
        SimulationProperties.OutputConfig out = props.getOutput();
        List<OutputAdapter> active = new ArrayList<>();

        if (out.isStdout()) {
            active.add(stdoutAdapter);
            log.info("Output: stdout enabled");
        }

        if (out.getFile().isEnabled()) {
            fileAdapter.configure(out.getFile().getTextPath(), out.getFile().getJsonPath());
            active.add(fileAdapter);
            log.info("Output: file enabled (text={}, json={})",
                    out.getFile().getTextPath(), out.getFile().getJsonPath());
        }

        if (out.getKafka().isEnabled()) {
            KafkaOutputAdapter kafka = new KafkaOutputAdapter(jsonFormatter, out.getKafka());
            active.add(kafka);
            log.info("Output: Kafka enabled (servers={}, topic={})",
                    out.getKafka().getBootstrapServers(), out.getKafka().getTopic());
        }

        if (active.isEmpty()) {
            log.warn("No output adapters enabled! Defaulting to stdout.");
            active.add(stdoutAdapter);
        }

        return new CompositeOutputAdapter(active);
    }
}
