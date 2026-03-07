package com.loggenengine.output;

import com.loggenengine.model.LogEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Fan-out adapter that delegates to all registered {@link OutputAdapter}s.
 */
@Slf4j
public class CompositeOutputAdapter implements OutputAdapter {

    private final List<OutputAdapter> adapters;

    public CompositeOutputAdapter(List<OutputAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    @Override
    public void open() throws Exception {
        for (OutputAdapter a : adapters) {
            a.open();
        }
        log.info("Composite output opened with {} adapter(s)", adapters.size());
    }

    @Override
    public void write(LogEvent event) throws Exception {
        for (OutputAdapter a : adapters) {
            a.write(event);
        }
    }

    @Override
    public void close() throws Exception {
        for (OutputAdapter a : adapters) {
            try { a.close(); } catch (Exception e) {
                log.warn("Error closing adapter {}: {}", a.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
