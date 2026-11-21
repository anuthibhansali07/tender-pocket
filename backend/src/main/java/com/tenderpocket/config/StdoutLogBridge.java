package com.tenderpocket.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

/**
 * Sends everything written to {@code System.out} / {@code System.err} through the logging system,
 * so it lands in the log file ({@code logging.file.name}) as well as on the console.
 *
 * <p>Most of the backend reports progress with {@code System.out.println} rather than a logger;
 * without this those lines (sync results, failures) would only ever reach the console.
 * Each complete line becomes one log event: {@code System.out} at INFO on logger "stdout",
 * {@code System.err} at ERROR on logger "stderr".
 *
 * <p>The console appender itself writes to {@code System.out}. Writes made while a line is being
 * logged on the same thread go straight to the original stream, so nothing loops.
 */
public final class StdoutLogBridge {

    private static volatile boolean installed;
    private static volatile PrintStream originalOut;

    private StdoutLogBridge() {}

    public static synchronized void install() {
        if (installed) return;
        originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(bridge(originalOut, LoggerFactory.getLogger("stdout"), false));
        System.setErr(bridge(originalErr, LoggerFactory.getLogger("stderr"), true));
        installed = true;
    }

    /**
     * The real console, before {@link #install()} replaced {@code System.out}. The console log
     * appender ({@link OriginalConsoleAppender}) writes here, so log lines are not fed back in as
     * "stdout" lines.
     */
    public static PrintStream originalOut() {
        PrintStream out = originalOut;
        return out != null ? out : System.out;
    }

    static PrintStream bridge(PrintStream original, Logger logger, boolean error) {
        Charset charset = original.charset();
        return new PrintStream(new LineToLogStream(original, logger, error, charset), true, charset);
    }

    /** Collects bytes per thread and logs each finished line. */
    static final class LineToLogStream extends OutputStream {
        /** Set while this thread is inside a logger call, so the console appender's own writes pass through. */
        private static final ThreadLocal<Boolean> LOGGING = ThreadLocal.withInitial(() -> false);

        private final PrintStream original;
        private final Logger logger;
        private final boolean error;
        private final Charset charset;
        private final ThreadLocal<ByteArrayOutputStream> line = ThreadLocal.withInitial(ByteArrayOutputStream::new);

        LineToLogStream(PrintStream original, Logger logger, boolean error, Charset charset) {
            this.original = original;
            this.logger = logger;
            this.error = error;
            this.charset = charset;
        }

        @Override
        public void write(int b) {
            if (LOGGING.get()) {
                original.write(b);
                return;
            }
            if (b == '\n') {
                emit();
            } else {
                line.get().write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (LOGGING.get()) {
                original.write(b, off, len);
                return;
            }
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (b[i] == '\n') {
                    line.get().write(b, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            if (start < end) line.get().write(b, start, end - start);
        }

        @Override
        public void flush() {
            if (LOGGING.get()) original.flush();
            // Partial lines wait for their newline so one println stays one log event.
        }

        private void emit() {
            ByteArrayOutputStream buf = line.get();
            String text = buf.toString(charset);
            buf.reset();
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '\r') end--;
            text = text.substring(0, end);
            LOGGING.set(true);
            try {
                if (error) logger.error(text);
                else logger.info(text);
            } catch (Throwable t) {
                original.println(text);
            } finally {
                LOGGING.set(false);
            }
        }
    }
}
