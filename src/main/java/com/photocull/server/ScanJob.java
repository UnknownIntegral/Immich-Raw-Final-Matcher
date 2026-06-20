package com.photocull.server;

import java.time.Instant;
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
    private volatile String error;

    ScanJob(Runnable onChange) {
        this(UUID.randomUUID().toString(), Instant.now(), onChange);
    }

    private ScanJob(String id, Instant startedAt, Runnable onChange) {
        this.id = id;
        this.startedAt = startedAt;
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    static ScanJob restored(Map<String, Object> values, Runnable onChange) {
        ScanJob job = new ScanJob(string(values.get("id"), UUID.randomUUID().toString()),
                instant(values.get("startedAt")), onChange);
        job.state = string(values.get("state"), "FAILED");
        job.phase = string(values.get("phase"), job.state.equals("RUNNING") ? "Interrupted" : job.state);
        job.message = string(values.get("message"), "Previous scan status is unavailable.");
        job.percent = number(values.get("percent"), -1);
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
                } catch (NumberFormatException ignored) {
                    percent = -1;
                }
            }
        } else if (this.message.startsWith("Finding duplicates")) {
            phase = "Finding duplicates";
            percent = -1;
        }
        changed();
    }

    void complete() {
        state = "COMPLETE";
        phase = "Complete";
        message = "Immich scan complete.";
        percent = 100;
        changed();
    }

    void fail(Exception exception) {
        state = "FAILED";
        phase = "Failed";
        error = exception.getMessage() == null ? "Immich scan failed." : exception.getMessage();
        message = error;
        percent = -1;
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
        changed();
    }

    Map<String, Object> json() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("startedAt", startedAt);
        values.put("state", state);
        values.put("phase", phase);
        values.put("message", message);
        values.put("percent", percent);
        values.put("error", error);
        return values;
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
