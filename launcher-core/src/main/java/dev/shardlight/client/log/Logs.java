package dev.shardlight.client.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Launcher logging on java.util.logging: {@code logs/launcher-N.log} (5 files of 2 MB, newest is
 * {@code launcher-0.log}) plus the console. Every line passes through {@link Redactor}.
 */
public final class Logs {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());
    private static volatile Path logFile;

    private Logs() {
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    /** the current launcher log, or null before {@link #init} */
    public static Path currentLogFile() {
        return logFile;
    }

    public static synchronized void init(Path logsDir, boolean console) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        Formatter f = new LineFormatter();
        try {
            Files.createDirectories(logsDir);
            FileHandler fh = new FileHandler(logsDir.resolve("launcher-%g.log").toString(), 2_000_000, 5, false);
            fh.setFormatter(f);
            fh.setEncoding("UTF-8");
            fh.setLevel(Level.FINE);
            root.addHandler(fh);
            logFile = logsDir.resolve("launcher-0.log");
        } catch (IOException e) {
            System.err.println("Shardlight Client: can't write logs to " + logsDir + ": " + e.getMessage());
        }
        if (console) {
            ConsoleHandler ch = new ConsoleHandler();
            ch.setFormatter(f);
            ch.setLevel(Level.INFO);
            root.addHandler(ch);
        }
        root.setLevel(Level.INFO);
        Logger.getLogger("dev.shardlight").setLevel(Level.FINE);
    }

    /** one line per record: time, level, logger, message (redacted), stack trace when present */
    public static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            String name = r.getLoggerName() == null ? "" : r.getLoggerName();
            int dot = name.lastIndexOf('.');
            StringBuilder b = new StringBuilder(160)
                .append(TIME.format(Instant.ofEpochMilli(r.getMillis())))
                .append(' ').append(r.getLevel().getName())
                .append(" [").append(dot >= 0 ? name.substring(dot + 1) : name).append("] ")
                .append(Redactor.redact(formatMessage(r)))
                .append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                b.append(Redactor.redact(sw.toString()));
            }
            return b.toString();
        }
    }
}
