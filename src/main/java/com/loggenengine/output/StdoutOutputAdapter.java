package com.loggenengine.output;

import com.loggenengine.formatter.TextLogFormatter;
import com.loggenengine.model.LogEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes log events to standard output in text format.
 * Only active when {@code simulation.output.stdout=true}.
 */
@Component
@RequiredArgsConstructor
public class StdoutOutputAdapter implements OutputAdapter {

    private final TextLogFormatter formatter;

    @Override
    public void open() {
        // no-op
    }

    @Override
    public void write(LogEvent event) {
        System.out.println(formatter.format(event));
    }

    @Override
    public void close() {
        System.out.flush();
    }
}
