package com.loggenengine.output;

import com.loggenengine.formatter.JsonLogFormatter;
import com.loggenengine.formatter.TextLogFormatter;
import com.loggenengine.model.LogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes log events to two files in parallel:
 * - A text (.log) file in Logback style
 * - A JSON Lines (.jsonl) file, one JSON object per line
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileOutputAdapter implements OutputAdapter {

    private final TextLogFormatter textFormatter;
    private final JsonLogFormatter jsonFormatter;

    private BufferedWriter textWriter;
    private BufferedWriter jsonWriter;

    private String textPath = "./output/simulation.log";
    private String jsonPath  = "./output/simulation.jsonl";

    public void configure(String textPath, String jsonPath) {
        this.textPath = textPath;
        this.jsonPath  = jsonPath;
    }

    @Override
    public void open() throws IOException {
        Path tp = Path.of(textPath);
        Path jp = Path.of(jsonPath);
        Files.createDirectories(tp.getParent());
        Files.createDirectories(jp.getParent());

        textWriter = Files.newBufferedWriter(tp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        jsonWriter = Files.newBufferedWriter(jp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.info("File output opened: text={}, json={}", textPath, jsonPath);
    }

    @Override
    public void write(LogEvent event) throws IOException {
        textWriter.write(textFormatter.format(event));
        textWriter.newLine();

        jsonWriter.write(jsonFormatter.format(event));
        jsonWriter.newLine();
    }

    @Override
    public void close() throws IOException {
        if (textWriter != null) textWriter.close();
        if (jsonWriter  != null) jsonWriter.close();
        log.info("File output closed.");
    }
}
