package com.loggenengine.formatter;

import com.loggenengine.model.LogEvent;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formats a LogEvent as a Logback-style text line.
 * Pattern: {@code YYYY-MM-DD HH:mm:ss.SSS LEVEL [instanceId] [trace=X,span=Y] logger - message}
 * ERROR events with a stack trace append it on subsequent lines.
 */
@Component
public class TextLogFormatter implements LogFormatter {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    @Override
    public String format(LogEvent event) {
        StringBuilder sb = new StringBuilder(256);

        sb.append(TS_FMT.format(event.getTimestamp()));
        sb.append(' ');
        sb.append(event.getLevel().padded());
        sb.append(" [").append(event.getInstanceId()).append(']');
        sb.append(" [trace=").append(event.getTraceId())
          .append(",span=").append(event.getSpanId()).append(']');
        sb.append(' ').append(event.getLogger());
        sb.append(" - ").append(event.getMessage());

        // Append selected structured fields as key=value pairs
        if (event.getFields() != null && !event.getFields().isEmpty()) {
            sb.append(" {");
            event.getFields().forEach((k, v) -> sb.append(k).append('=').append(v).append(", "));
            sb.setLength(sb.length() - 2); // remove trailing ", "
            sb.append('}');
        }

        // Append stack trace for error events
        if (event.getStackTrace() != null) {
            sb.append('\n').append(event.getStackTrace());
        }

        return sb.toString();
    }
}
