package com.tenderpocket.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdoutLogBridgeTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("stdout-bridge-test");
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void eachPrintlnBecomesOneLogEvent() {
        PrintStream original = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream bridged = StdoutLogBridge.bridge(original, logger, false);

        bridged.print("[StateSync] KL ");
        bridged.print("done: imported=3");
        bridged.println();
        bridged.printf("[StateSync] %s failed: %s%n", "MN", "timeout");
        bridged.println("windows line\r");

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(List.of("[StateSync] KL done: imported=3", "[StateSync] MN failed: timeout", "windows line"), lines);
        assertTrue(appender.list.stream().allMatch(e -> e.getLevel() == Level.INFO));
    }

    @Test
    void stderrLinesAreLoggedAsErrors() {
        PrintStream original = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        PrintStream bridged = StdoutLogBridge.bridge(original, logger, true);

        bridged.println("[Scheduler] GeM Sync failed: boom");

        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
    }

    @Test
    void writesFromInsideLoggingGoToOriginalStreamWithoutLooping() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        PrintStream original = new PrintStream(raw, true, StandardCharsets.UTF_8);
        PrintStream[] bridgedRef = new PrintStream[1];

        // Stand-in for a console appender that writes to System.out, which is the bridge itself.
        ch.qos.logback.core.AppenderBase<ILoggingEvent> console = new ch.qos.logback.core.AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                bridgedRef[0].println("CONSOLE " + event.getFormattedMessage());
            }
        };
        console.start();
        logger.addAppender(console);
        try {
            bridgedRef[0] = StdoutLogBridge.bridge(original, logger, false);
            bridgedRef[0].println("hello");
        } finally {
            logger.detachAppender(console);
        }

        assertEquals(1, appender.list.size());
        assertEquals("CONSOLE hello" + System.lineSeparator(), raw.toString(StandardCharsets.UTF_8));
    }
}
