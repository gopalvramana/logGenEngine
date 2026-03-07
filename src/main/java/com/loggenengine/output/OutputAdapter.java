package com.loggenengine.output;

import com.loggenengine.model.LogEvent;

/**
 * Sink for log events produced by the simulation engine.
 */
public interface OutputAdapter {
    void open() throws Exception;
    void write(LogEvent event) throws Exception;
    void close() throws Exception;
}
