package com.photocull.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent state for one plan application. A failed or interrupted operation
 * can safely resume: completed steps are never sent again and incomplete tag
 * adds/removals are idempotent at the Immich API boundary.
 */
public final class PlanApplyOperation {
    public enum State { RUNNING, COMPLETE, FAILED }
    public enum StepState { PENDING, RUNNING, COMPLETE, FAILED }
    public enum Mutation { ADD, REMOVE, CLEAR }
    public enum Resource { TAG, ALBUM }

    private final String id;
    private final String planId;
    private final String planFingerprint;
    private final Instant createdAt;
    private Instant updatedAt;
    private State state;
    private String error;
    private final List<Step> steps;

    private PlanApplyOperation(
            String id,
            String planId,
            String planFingerprint,
            Instant createdAt,
            Instant updatedAt,
            State state,
            String error,
            List<Step> steps
    ) {
        this.id = id;
        this.planId = planId;
        this.planFingerprint = planFingerprint;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.state = state;
        this.error = error;
        this.steps = new ArrayList<>(steps);
    }

    public static PlanApplyOperation create(ImmutableTagPlan plan) {
        Map<String, String> tags = plan.decisionTags();
        List<String> keepers = ids(plan.rawItems(), ImmutableTagPlan.KEEPER);
        List<String> unused = ids(plan.rawItems(), ImmutableTagPlan.UNUSED);
        List<String> finalNotFound = ids(plan.rawItems(), ImmutableTagPlan.FINAL_NOT_FOUND);
        List<String> rawFound = ids(plan.finalItems(), ImmutableTagPlan.RAW_FOUND);
        List<String> noRaw = ids(plan.finalItems(), ImmutableTagPlan.NO_RAW);
        List<String> duplicates = ids(plan.finalItems(), ImmutableTagPlan.DUPLICATE);
        List<String> allRawDecisionIds = allRawDecisionIds(plan.rawItems());
        List<String> allFinalDecisionIds = allFinalDecisionIds(plan.finalItems());

        List<Step> steps = new ArrayList<>(List.of(
                Step.pending("remove-raw-keeper", ImmutableTagPlan.RAW, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.KEEPER), allRawDecisionIds),
                Step.pending("remove-raw-unused", ImmutableTagPlan.RAW, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.UNUSED), allRawDecisionIds),
                Step.pending("remove-raw-final-not-found", ImmutableTagPlan.RAW, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.FINAL_NOT_FOUND), allRawDecisionIds),
                Step.pending("remove-final-raw-found", ImmutableTagPlan.FINAL, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.RAW_FOUND), allFinalDecisionIds),
                Step.pending("remove-final-no-raw", ImmutableTagPlan.FINAL, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.NO_RAW), allFinalDecisionIds),
                Step.pending("remove-final-duplicate", ImmutableTagPlan.FINAL, Mutation.REMOVE,
                        tag(tags, ImmutableTagPlan.DUPLICATE), allFinalDecisionIds),
                Step.pending("add-raw-keeper", ImmutableTagPlan.RAW, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.KEEPER), keepers),
                Step.pending("add-raw-unused", ImmutableTagPlan.RAW, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.UNUSED), unused),
                Step.pending("add-raw-final-not-found", ImmutableTagPlan.RAW, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.FINAL_NOT_FOUND), finalNotFound),
                Step.pending("add-final-raw-found", ImmutableTagPlan.FINAL, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.RAW_FOUND), rawFound),
                Step.pending("add-final-no-raw", ImmutableTagPlan.FINAL, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.NO_RAW), noRaw),
                Step.pending("add-final-duplicate", ImmutableTagPlan.FINAL, Mutation.ADD,
                        tag(tags, ImmutableTagPlan.DUPLICATE), duplicates)
        ));
        addAlbumClearSteps(steps, ImmutableTagPlan.RAW, plan, ImmutableTagPlan.KEEPER, ImmutableTagPlan.UNUSED,
                ImmutableTagPlan.FINAL_NOT_FOUND);
        addAlbumClearSteps(steps, ImmutableTagPlan.FINAL, plan, ImmutableTagPlan.RAW_FOUND, ImmutableTagPlan.NO_RAW,
                ImmutableTagPlan.DUPLICATE);
        addAlbumStep(steps, "add-raw-keeper-to-album", ImmutableTagPlan.RAW, plan, ImmutableTagPlan.KEEPER, keepers);
        addAlbumStep(steps, "add-raw-unused-to-album", ImmutableTagPlan.RAW, plan, ImmutableTagPlan.UNUSED, unused);
        addAlbumStep(steps, "add-raw-final-not-found-to-album", ImmutableTagPlan.RAW, plan, ImmutableTagPlan.FINAL_NOT_FOUND, finalNotFound);
        addAlbumStep(steps, "add-final-raw-found-to-album", ImmutableTagPlan.FINAL, plan, ImmutableTagPlan.RAW_FOUND, rawFound);
        addAlbumStep(steps, "add-final-no-raw-to-album", ImmutableTagPlan.FINAL, plan, ImmutableTagPlan.NO_RAW, noRaw);
        addAlbumStep(steps, "add-final-duplicate-to-album", ImmutableTagPlan.FINAL, plan, ImmutableTagPlan.DUPLICATE, duplicates);
        Instant now = Instant.now();
        return new PlanApplyOperation("apply-" + plan.id(), plan.id(), plan.fingerprint(), now, now,
                State.RUNNING, null, steps);
    }

    public synchronized void begin() {
        if (state == State.COMPLETE) {
            return;
        }
        state = State.RUNNING;
        error = null;
        touch();
    }

    public synchronized void start(Step step) {
        step.start();
        touch();
    }

    public synchronized void complete(Step step, int affectedAssets) {
        step.complete(affectedAssets);
        if (steps.stream().allMatch(candidate -> candidate.state == StepState.COMPLETE)) {
            state = State.COMPLETE;
            error = null;
        }
        touch();
    }

    /** Records fully acknowledged batches so a retry resumes after them. */
    public synchronized void progress(Step step, int affectedAssets) {
        step.progress(affectedAssets);
        touch();
    }

    public synchronized void fail(Step step, Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        step.fail(message);
        state = State.FAILED;
        error = message;
        touch();
    }

    public synchronized boolean isComplete() {
        return state == State.COMPLETE;
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }

    public synchronized int affectedAssets(String stepId) {
        return steps.stream().filter(step -> step.id.equals(stepId)).findFirst()
                .map(step -> step.affectedAssets).orElse(0);
    }

    public String id() {
        return id;
    }

    public String planId() {
        return planId;
    }

    public String planFingerprint() {
        return planFingerprint;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized String error() {
        return error;
    }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("planId", planId);
        values.put("planFingerprint", planFingerprint);
        values.put("createdAt", createdAt);
        values.put("updatedAt", updatedAt);
        values.put("state", state.name());
        values.put("error", error);
        values.put("completedSteps", steps.stream().filter(step -> step.state == StepState.COMPLETE).count());
        values.put("stepCount", steps.size());
        return values;
    }

    synchronized Map<String, Object> toJson() {
        Map<String, Object> values = new LinkedHashMap<>(summary());
        values.put("version", 1);
        values.put("steps", steps.stream().map(Step::toJson).toList());
        return values;
    }

    static PlanApplyOperation fromJson(Map<String, Object> values) {
        List<Step> steps = array(values.get("steps")).stream().map(item -> Step.fromJson(object(item))).toList();
        return new PlanApplyOperation(string(values.get("id")), string(values.get("planId")),
                string(values.get("planFingerprint")), instant(values.get("createdAt")), instant(values.get("updatedAt")),
                state(values.get("state"), State.FAILED), nullable(values.get("error")), steps);
    }

    private static List<String> ids(List<ImmutableTagPlan.PlanItem> items, String decision) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ImmutableTagPlan.PlanItem item : items) {
            if (decision.equals(item.decision())) {
                ids.add(item.assetId());
            }
        }
        return List.copyOf(ids);
    }

    private static List<String> allFinalDecisionIds(List<ImmutableTagPlan.PlanItem> items) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ImmutableTagPlan.PlanItem item : items) {
            if (ImmutableTagPlan.RAW_FOUND.equals(item.decision()) || ImmutableTagPlan.NO_RAW.equals(item.decision())) {
                ids.add(item.assetId());
            }
        }
        return List.copyOf(ids);
    }

    private static List<String> allRawDecisionIds(List<ImmutableTagPlan.PlanItem> items) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ImmutableTagPlan.PlanItem item : items) {
            if (ImmutableTagPlan.KEEPER.equals(item.decision()) || ImmutableTagPlan.UNUSED.equals(item.decision())
                    || ImmutableTagPlan.FINAL_NOT_FOUND.equals(item.decision())) {
                ids.add(item.assetId());
            }
        }
        return List.copyOf(ids);
    }

    private static String tag(Map<String, String> tags, String decision) {
        String value = tags.get(decision);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Frozen tag plan is missing the " + decision + " tag.");
        }
        return value;
    }

    private static void addAlbumClearSteps(List<Step> steps, String account, ImmutableTagPlan plan, String... decisions) {
        Set<String> seen = new LinkedHashSet<>();
        for (String decision : decisions) {
            String album = plan.decisionAlbums().get(decision);
            if (album == null || album.isBlank()) {
                continue;
            }
            String key = account.toLowerCase() + "\n" + album.toLowerCase();
            if (seen.add(key)) {
                steps.add(Step.pendingAlbum("clear-" + account.toLowerCase() + "-" + decision.toLowerCase() + "-album",
                        account, Mutation.CLEAR, album, List.of()));
            }
        }
    }

    private static void addAlbumStep(
            List<Step> steps,
            String id,
            String account,
            ImmutableTagPlan plan,
            String decision,
            List<String> assetIds
    ) {
        String album = plan.decisionAlbums().get(decision);
        if (album != null && !album.isBlank() && !assetIds.isEmpty()) {
            steps.add(Step.pendingAlbum(id, account, Mutation.ADD, album, assetIds));
        }
    }

    private synchronized void touch() {
        updatedAt = Instant.now();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<Object> array(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullable(Object value) {
        String text = string(value);
        return text.isBlank() ? null : text;
    }

    private static Instant instant(Object value) {
        try {
            return Instant.parse(string(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stored apply operation contains an invalid timestamp.", ex);
        }
    }

    private static <T extends Enum<T>> T state(Object value, T fallback) {
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), string(value));
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static final class Step {
        private final String id;
        private final String account;
        private final Resource resource;
        private final Mutation mutation;
        private final String tag;
        private final List<String> assetIds;
        private StepState state;
        private int affectedAssets;
        private String error;

        private Step(String id, String account, Resource resource, Mutation mutation, String tag, List<String> assetIds,
                     StepState state, int affectedAssets, String error) {
            this.id = id;
            this.account = account;
            this.resource = resource;
            this.mutation = mutation;
            this.tag = tag;
            this.assetIds = List.copyOf(assetIds);
            this.state = state;
            this.affectedAssets = affectedAssets;
            this.error = error;
        }

        private static Step pending(String id, String account, Mutation mutation, String tag, List<String> assetIds) {
            return new Step(id, account, Resource.TAG, mutation, tag, assetIds, StepState.PENDING, 0, null);
        }

        private static Step pendingAlbum(String id, String account, Mutation mutation, String album, List<String> assetIds) {
            return new Step(id, account, Resource.ALBUM, mutation, album, assetIds, StepState.PENDING, 0, null);
        }

        public String id() { return id; }
        public String account() { return account; }
        public Resource resource() { return resource; }
        public Mutation mutation() { return mutation; }
        public String tag() { return tag; }
        public List<String> assetIds() { return assetIds; }
        public StepState state() { return state; }
        public int affectedAssets() { return affectedAssets; }
        public String error() { return error; }

        private void start() {
            state = StepState.RUNNING;
            error = null;
        }

        private void complete(int count) {
            state = StepState.COMPLETE;
            affectedAssets = count;
            error = null;
        }

        private void progress(int count) {
            if (count < affectedAssets || count > assetIds.size()) {
                throw new IllegalArgumentException("Invalid acknowledged asset count for apply step " + id + ".");
            }
            affectedAssets = count;
        }

        private void fail(String message) {
            state = StepState.FAILED;
            error = message;
        }

        private Map<String, Object> toJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("account", account);
            values.put("resource", resource.name());
            values.put("mutation", mutation.name());
            values.put("tag", tag);
            values.put("assetIds", assetIds);
            values.put("state", state.name());
            values.put("affectedAssets", affectedAssets);
            values.put("error", error);
            return values;
        }

        private static Step fromJson(Map<String, Object> values) {
            List<String> ids = array(values.get("assetIds")).stream().map(PlanApplyOperation::string).toList();
            return new Step(string(values.get("id")), string(values.get("account")),
                    PlanApplyOperation.state(values.get("resource"), Resource.TAG),
                    PlanApplyOperation.state(values.get("mutation"), Mutation.ADD), string(values.get("tag")), ids,
                    PlanApplyOperation.state(values.get("state"), StepState.FAILED), number(values.get("affectedAssets")),
                    nullable(values.get("error")));
        }

        private static int number(Object value) {
            return value instanceof Number number ? number.intValue() : 0;
        }
    }
}
