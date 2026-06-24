package com.photocull.server;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory progress for a user-initiated operation that must not occupy an
 * HTTP request thread.  The immutable plan and apply checkpoints remain
 * durable in PlanStore; this object only reports live execution state.
 */
final class OperationJob {
    private final String id = UUID.randomUUID().toString();
    private final String kind;
    private final Instant startedAt = Instant.now();
    private volatile Instant updatedAt = startedAt;
    private volatile String state = "RUNNING";
    private volatile String phase;
    private volatile String message;
    private volatile int completed;
    private volatile int total = -1;
    private volatile String error;

    OperationJob(String kind, String phase, String message) {
        this.kind = kind;
        this.phase = phase;
        this.message = message;
    }

    String id() {
        return id;
    }

    String kind() {
        return kind;
    }

    boolean running() {
        return "RUNNING".equals(state);
    }

    void progress(String phase, String message, int completed, int total) {
        this.phase = phase;
        this.message = message;
        this.completed = Math.max(0, completed);
        this.total = total < 0 ? -1 : Math.max(this.completed, total);
        this.updatedAt = Instant.now();
    }

    void complete(String message) {
        this.state = "COMPLETE";
        this.phase = "Complete";
        this.message = message;
        if (total >= 0) {
            completed = total;
        }
        this.updatedAt = Instant.now();
    }

    void fail(Exception failure) {
        this.state = "FAILED";
        this.phase = "Failed";
        this.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        this.message = error;
        this.updatedAt = Instant.now();
    }

    Map<String, Object> json() {
        Map<String, Object> values = new LinkedHashMap<>();
        long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
        values.put("id", id);
        values.put("kind", kind);
        values.put("startedAt", startedAt);
        values.put("updatedAt", updatedAt);
        values.put("elapsedMillis", elapsedMillis);
        values.put("state", state);
        values.put("phase", phase);
        values.put("message", message);
        values.put("completed", completed);
        values.put("total", total);
        values.put("percent", total > 0 ? Math.min(100, (int) Math.round(100.0 * completed / total)) : -1);
        values.put("estimatedRemainingMillis", estimateRemainingMillis(elapsedMillis));
        values.put("error", error);
        return values;
    }

    private Long estimateRemainingMillis(long elapsedMillis) {
        if (!running() || total <= completed || completed <= 0 || elapsedMillis < 1_000) {
            return null;
        }
        return Math.round((double) elapsedMillis * (total - completed) / completed);
    }
}
