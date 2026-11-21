package com.tenderpocket.config;

import ch.qos.logback.core.OutputStreamAppender;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Console appender that writes to the real console saved by {@link StdoutLogBridge}, not to the
 * current {@code System.out}. Logback's own ConsoleAppender writes to {@code System.out}, which the
 * bridge has replaced, so every logger line would be logged a second time as a "stdout" line.
 * Used from logback-spring.xml.
 */
public class OriginalConsoleAppender<E> extends OutputStreamAppender<E> {

    private final OutputStream target;

    public OriginalConsoleAppender() {
        this(null);
    }

    /** For tests: write to {@code target} instead of the console. */
    OriginalConsoleAppender(OutputStream target) {
        this.target = target;
    }

    @Override
    public void start() {
        OutputStream out = target != null ? target : StdoutLogBridge.originalOut();
        setOutputStream(new NonClosing(out));
        super.start();
    }

    /** Stopping the appender must not close the process's console. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
