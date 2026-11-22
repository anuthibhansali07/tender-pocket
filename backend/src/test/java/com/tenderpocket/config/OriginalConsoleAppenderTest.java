package com.tenderpocket.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OriginalConsoleAppenderTest {

    @Test
    void writesToTheGivenConsoleAndNeverClosesIt() {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        boolean[] closed = {false};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream console = new OutputStream() {
            @Override public void write(int b) { bytes.write(b); }
            @Override public void close() { closed[0] = true; }
        };

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%logger: %msg%n");
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        OriginalConsoleAppender<ILoggingEvent> appender = new OriginalConsoleAppender<>(console);
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.start();

        Logger logger = context.getLogger("c.tenderpocket.TenderPocketApplication");
        logger.setAdditive(false);
        logger.addAppender(appender);
        logger.info("Started TenderPocketApplication");
        appender.stop();

        assertEquals("c.tenderpocket.TenderPocketApplication: Started TenderPocketApplication" + System.lineSeparator(),
                bytes.toString(StandardCharsets.UTF_8));
        assertFalse(closed[0], "stopping the appender must not close the console");
    }
}
