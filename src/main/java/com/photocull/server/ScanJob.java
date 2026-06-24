package com.photocull.server;

import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ScanJob {
    private final String id;
    private final Instant startedAt;
    private final Runnable onChange;
    private volatile String state = "RUNNING";
    private volatile String phase = "Starting";
    private volatile String message = "Starting Immich scan...";
    private volatile int percent = -1;
    private volatile int completed;
    private volatile int total = -1;
    private volatile Instant updatedAt;
    private volatile String error;

    ScanJob(Runnable onChange) {
        this(UUID.randomUUID().toString(), Instant.now(), onChange);
    }

    private ScanJob(String id, Instant startedAt, Runnable onChange) {
        this.id = id;
        this.startedAt = startedAt;
        this.onChange = onChange == null ? () -> { } : onChange;
        this.updatedAt = startedAt;
    }

    static ScanJob restored(Map<String, Object> values, Runnable onChange) {
        ScanJob job = new ScanJob(string(values.get("id"), UUID.randomUUID().toString()),
                instant(values.get("startedAt")), onChange);
        job.state = string(values.get("state"), "FAILED");
        job.phase = string(values.get("phase"), job.state.equals("RUNNING") ? "Interrupted" : job.state);
        job.message = string(values.get("message"), "Previous scan status is unavailable.");
        job.percent = number(values.get("percent"), -1);
        job.completed = number(values.get("completed"), 0);
        job.total = number(values.get("total"), -1);
        job.error = string(values.get("error"), null);
        return job;
    }

    String id() {
        return id;
    }

    void progress(String message) {
        this.message = message == null ? "Working..." : message;
        if (this.message.startsWith("Reading RAW")) {
            phase = "Reading RAW assets";
            percent = -1;
        } else if (this.message.startsWith("Reading edited")) {
            phase = "Reading final assets";
            percent = -1;
        } else if (this.message.startsWith("Matching ")) {
            phase = "Matching images";
            percent = 0;
        } else if (this.message.startsWith("Matched ")) {
            phase = "Matching images";
            String[] values = this.message.replaceAll("[^0-9]+", " ").trim().split(" ");
            if (values.length >= 2) {
                try {
                    percent = Math.min(100, (int) Math.round(100.0 * Integer.parseInt(values[0]) / Integer.parseInt(values[1])));
                    completed = Integer.parseInt(values[0]);
                    total = Integer.parseInt(values[1]);
                } catch (NumberFormatException ignored) {
                    percent = -1;
                }
            }
        } else if (this.message.startsWith("Finding duplicates")) {
            phase = "Finding duplicates";
            percent = -1;
        }
        if (this.message.startsWith("Read Immich page ")) {
            java.util.regex.Matcher values = java.util.regex.Pattern
                    .compile(":\\s*(\\d+) matching assets,\\s*(\\d+) total")
                    .matcher(this.message);
            if (values.find()) {
                try {
                    completed = Integer.parseInt(values.group(2));
                    total = -1;
                } catch (NumberFormatException ignored) {
                    // The human-readable progress message remains available.
                }
            }
        }
        updatedAt = Instant.now();
        if (this.message.startsWith("Reading ") || this.message.startsWith("Finding ") || this.message.startsWith("Writing ")) {
            AppLog.info("scan.phase", Map.of("scanId", AppLog.shortId(id), "phase", phase, "message", this.message));
        } else {
            AppLog.debug("scan.progress", Map.of("scanId", AppLog.shortId(id), "phase", phase, "percent", percent));
        }
        changed();
    }

    void complete() {
        state = "COMPLETE";
        phase = "Complete";
        message = "Immich scan complete.";
        percent = 100;
        if (total >= 0) {
            completed = total;
        }
        updatedAt = Instant.now();
        AppLog.info("scan.job_complete", Map.of("scanId", AppLog.shortId(id)));
        changed();
    }

    void fail(Exception exception) {
        state = "FAILED";
        phase = "Failed";
        error = exception.getMessage() == null ? "Immich scan failed." : exception.getMessage();
        message = error;
        percent = -1;
        updatedAt = Instant.now();
        AppLog.error("scan.job_failed", Map.of("scanId", AppLog.shortId(id)), exception);
        changed();
    }

    void interrupt() {
        if (!"RUNNING".equals(state)) {
            return;
        }
        state = "INTERRUPTED";
        phase = "Interrupted";
        error = "The server restarted before this scan finished. Start a new scan to continue.";
        message = error;
        percent = -1;
        updatedAt = Instant.now();
        AppLog.warn("scan.job_interrupted", Map.of("scanId", AppLog.shortId(id)));
        changed();
    }

    Map<String, Object> json() {
        Map<String, Object> values = new LinkedHashMap<>();
        long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
        values.put("id", id);
        values.put("startedAt", startedAt);
        values.put("updatedAt", updatedAt);
        values.put("elapsedMillis", elapsedMillis);
        values.put("state", state);
        values.put("phase", phase);
        values.put("message", message);
        values.put("percent", percent);
        values.put("completed", completed);
        values.put("total", total);
        values.put("estimatedRemainingMillis", estimateRemainingMillis(elapsedMillis));
        values.put("error", error);
        return values;
    }

    private Long estimateRemainingMillis(long elapsedMillis) {
        if (!"RUNNING".equals(state) || total <= completed || completed <= 0 || elapsedMillis < 1_000) {
            return null;
        }
        return Math.round((double) elapsedMillis * (total - completed) / completed);
    }

    private void changed() {
        onChange.run();
    }

    private static String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Instant instant(Object value) {
        try {
            return value == null ? Instant.now() : Instant.parse(value.toString());
        } catch (Exception ignored) {
            return Instant.now();
        }
    }
}
