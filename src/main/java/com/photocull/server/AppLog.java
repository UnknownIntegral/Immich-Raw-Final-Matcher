package com.photocull.server;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small dependency-free JSON logger for the container.  The Unraid Docker log
 * remains the primary live view; the same records are mirrored into /config.
 */
public final class AppLog {
    private static final int RETENTION_DAYS = 7;
    private static final Level CONFIGURED_LEVEL = Level.fromEnvironment();

    private AppLog() {
    }

    static void install(Path configDir) throws IOException {
        Path directory = configDir.resolve("logs");
        Files.createDirectories(directory);
        deleteExpired(directory);
        PrintStream stdout = System.out;
        PrintStream stderr = System.err;
        System.setOut(new PrintStream(new DailyTeeOutputStream(stdout, directory), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new DailyTeeOutputStream(stderr, directory), true, StandardCharsets.UTF_8));
        info("logging.initialized", Map.of(
                "directory", directory.toString(),
                "retentionDays", RETENTION_DAYS,
                "level", CONFIGURED_LEVEL.name()));
    }

    public static void info(String event, Map<String, ?> fields) {
        log(Level.INFO, event, fields, null);
    }

    public static void warn(String event, Map<String, ?> fields) {
        log(Level.WARN, event, fields, null);
    }

    public static void error(String event, Map<String, ?> fields, Throwable failure) {
        log(Level.ERROR, event, fields, failure);
    }

    public static void debug(String event, Map<String, ?> fields) {
        log(Level.DEBUG, event, fields, null);
    }

    public static String shortId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static synchronized void log(Level level, String event, Map<String, ?> fields, Throwable failure) {
        if (level.ordinal() < CONFIGURED_LEVEL.ordinal()) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", Instant.now().toString());
        record.put("level", level.name());
        record.put("event", event);
        if (fields != null) {
            record.putAll(fields);
        }
        if (failure != null) {
            record.put("exception", failure.getClass().getName());
            record.put("message", safeMessage(failure.getMessage()));
        }
        System.out.println(Json.object(record));
        if (failure != null && level == Level.ERROR && CONFIGURED_LEVEL == Level.DEBUG) {
            failure.printStackTrace(System.err);
        }
    }

    private static String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message
                .replaceAll("(?i)(x-api-key|token|api[_-]?key)\\s*[:=]\\s*[^\\s,]+", "$1=[redacted]")
                .replaceAll("(?i)([?&](?:token|api[_-]?key)=)[^&\\s]+", "$1[redacted]")
                .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1[redacted]@");
    }

    private enum Level {
        DEBUG, INFO, WARN, ERROR;

        private static Level fromEnvironment() {
            String value = System.getenv("PCA_LOG_LEVEL");
            if (value == null || value.isBlank()) {
                return INFO;
            }
            try {
                return Level.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return INFO;
            }
        }
    }

    private static void deleteExpired(Path directory) throws IOException {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static final class DailyTeeOutputStream extends OutputStream {
        private final PrintStream console;
        private final Path directory;
        private LocalDate openDate;
        private OutputStream file;

        private DailyTeeOutputStream(PrintStream console, Path directory) {
            this.console = console;
            this.directory = directory;
        }

        @Override
        public synchronized void write(int value) throws IOException {
            console.write(value);
            currentFile().write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            currentFile().write(bytes, offset, length);
        }

        @Override
        public synchronized void flush() throws IOException {
            console.flush();
            if (file != null) {
                file.flush();
            }
        }

        private OutputStream currentFile() throws IOException {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(openDate)) {
                if (file != null) {
                    file.close();
                }
                openDate = today;
                deleteExpired(directory);
                file = Files.newOutputStream(directory.resolve("photo-culling-assistant-" + today + ".log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return file;
        }
    }
}
