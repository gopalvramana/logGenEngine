package com.loggenengine;

import com.loggenengine.core.SimulationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Deterministic Log Simulation Engine.
 *
 * <p>Kafka auto-configuration is excluded so the app starts cleanly without
 * a Kafka broker — the KafkaOutputAdapter is wired manually only when enabled.
 */
@Slf4j
@SpringBootApplication(exclude = {KafkaAutoConfiguration.class})
@EnableConfigurationProperties
@RequiredArgsConstructor
public class LogGenEngineApplication implements CommandLineRunner {

    private final SimulationEngine engine;

    public static void main(String[] args) {
        SpringApplication.run(LogGenEngineApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        engine.run();
    }
}
