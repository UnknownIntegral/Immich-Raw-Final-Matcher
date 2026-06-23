package com.photocull.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Durable storage for immutable plans and their resumable apply operations. */
final class PlanStore {
    private final Path plansDirectory;
    private final Path operationsDirectory;
    private final Path activePlanFile;

    PlanStore(Path configDir) {
        plansDirectory = configDir.resolve("tag-plans");
        operationsDirectory = configDir.resolve("tag-operations");
        activePlanFile = configDir.resolve("active-tag-plan.json");
    }

    synchronized ImmutableTagPlan freeze(ImmutableTagPlan plan) throws IOException {
        Files.createDirectories(plansDirectory);
        Path manifest = plansDirectory.resolve("photo-culling-dry-run-" + plan.id() + ".csv");
        ImmutableTagPlan stored = plan.withManifest(manifest);
        writeJson(planFile(stored.id()), stored.toJson());
        new DryRunManifestWriter().writeCsv(stored, manifest);
        Map<String, Object> active = new java.util.LinkedHashMap<>();
        active.put("planId", stored.id());
        writeJson(activePlanFile, active);
        return stored;
    }

    synchronized Optional<ImmutableTagPlan> loadActive() throws IOException {
        if (!Files.exists(activePlanFile)) {
            return Optional.empty();
        }
        String id = string(Json.parseObject(Files.readString(activePlanFile, StandardCharsets.UTF_8)).get("planId"));
        if (id.isBlank() || !Files.exists(planFile(id))) {
            return Optional.empty();
        }
        return Optional.of(ImmutableTagPlan.fromJson(
                Json.parseObject(Files.readString(planFile(id), StandardCharsets.UTF_8))));
    }

    synchronized void clearActive() throws IOException {
        Files.deleteIfExists(activePlanFile);
    }

    /** Removes all locally saved plans and resumable apply-operation checkpoints. */
    synchronized void clear() throws IOException {
        Files.deleteIfExists(activePlanFile);
        deleteDirectory(plansDirectory);
        deleteDirectory(operationsDirectory);
    }

    synchronized PlanApplyOperation loadOrCreateOperation(ImmutableTagPlan plan) throws IOException {
        Path path = operationFile(plan.id());
        if (Files.exists(path)) {
            PlanApplyOperation operation = PlanApplyOperation.fromJson(
                    Json.parseObject(Files.readString(path, StandardCharsets.UTF_8)));
            if (!operation.planId().equals(plan.id()) || !operation.planFingerprint().equals(plan.fingerprint())) {
                throw new IOException("Stored apply operation does not match the approved tag plan.");
            }
            return operation;
        }
        PlanApplyOperation operation = PlanApplyOperation.create(plan);
        saveOperation(operation);
        return operation;
    }

    synchronized Optional<PlanApplyOperation> loadOperation(ImmutableTagPlan plan) throws IOException {
        Path path = operationFile(plan.id());
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        PlanApplyOperation operation = PlanApplyOperation.fromJson(
                Json.parseObject(Files.readString(path, StandardCharsets.UTF_8)));
        if (!operation.planId().equals(plan.id()) || !operation.planFingerprint().equals(plan.fingerprint())) {
            throw new IOException("Stored apply operation does not match the approved tag plan.");
        }
        return Optional.of(operation);
    }

    synchronized void saveOperation(PlanApplyOperation operation) throws IOException {
        Files.createDirectories(operationsDirectory);
        writeJson(operationFile(operation.planId()), operation.toJson());
    }

    private Path planFile(String id) {
        return plansDirectory.resolve("plan-" + safeId(id) + ".json");
    }

    private Path operationFile(String planId) {
        return operationsDirectory.resolve("apply-" + safeId(planId) + ".json");
    }

    private void writeJson(Path target, Map<String, Object> values) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, Json.object(values), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid plan identifier.");
        }
        return id;
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
