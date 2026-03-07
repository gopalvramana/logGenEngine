package com.loggenengine.formatter;

import com.loggenengine.model.LogEvent;

/**
 * Converts a LogEvent into its string representation.
 */
public interface LogFormatter {
    /**
     * Format the event. For events with stack traces the text formatter
     * appends the stack trace on subsequent lines.
     */
    String format(LogEvent event);
}
