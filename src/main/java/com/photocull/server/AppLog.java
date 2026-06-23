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

/**
 * Mirrors container stdout/stderr to persistent, daily log files under the
 * mapped config directory. Files older than seven days are removed at startup.
 */
final class AppLog {
    private static final int RETENTION_DAYS = 7;

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
