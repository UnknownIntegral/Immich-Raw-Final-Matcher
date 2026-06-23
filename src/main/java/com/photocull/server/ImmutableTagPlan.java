package com.photocull.server;

import com.photocull.immich.ImmichConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A durable, exact snapshot of the tag mutations that a user reviewed.
 *
 * The fingerprint deliberately covers every item and configured decision tag.
 * Applying a plan therefore never re-evaluates the mutable scan session.
 */
public final class ImmutableTagPlan {
    public static final String RAW = "RAW";
    public static final String FINAL = "FINAL";
    public static final String KEEPER = "KEEPER";
    public static final String UNUSED = "UNUSED";
    public static final String RAW_FOUND = "RAW_FOUND";
    public static final String NO_RAW = "NO_RAW";
    public static final String DUPLICATE = "DUPLICATE";

    private final String id;
    private final Instant createdAt;
    private final Instant sessionCreatedAt;
    private final String fingerprint;
    private final Map<String, String> decisionTags;
    private final Map<String, String> decisionAlbums;
    private final List<PlanItem> rawItems;
    private final List<PlanItem> finalItems;
    private final Path manifest;

    private ImmutableTagPlan(
            String id,
            Instant createdAt,
            Instant sessionCreatedAt,
            String fingerprint,
            Map<String, String> decisionTags,
            Map<String, String> decisionAlbums,
            List<PlanItem> rawItems,
            List<PlanItem> finalItems,
            Path manifest
    ) {
        this.id = id;
        this.createdAt = createdAt;
        this.sessionCreatedAt = sessionCreatedAt;
        this.fingerprint = fingerprint;
        this.decisionTags = Map.copyOf(decisionTags);
        this.decisionAlbums = Map.copyOf(decisionAlbums);
        this.rawItems = List.copyOf(rawItems);
        this.finalItems = List.copyOf(finalItems);
        this.manifest = manifest;
    }

    public static ImmutableTagPlan fromSession(ScanSession session, ImmichConfig config) {
        if (session.rawReviewCount() > 0) {
            throw new IllegalStateException("Resolve all RAW matches before creating a dry-run plan.");
        }

        Map<String, String> tags = decisionTags(config);
        Map<String, String> albums = decisionAlbums(config);
        Map<String, String> fileNames = FilenamePlanner.plan(session);
        validateDistinctTags(tags, RAW, KEEPER, UNUSED);
        validateDistinctTags(tags, FINAL, RAW_FOUND, NO_RAW, DUPLICATE);

        List<PlanItem> rawItems = new ArrayList<>();
        for (TagPlanItem item : session.tagPlan(tags.get(KEEPER), tags.get(UNUSED))) {
            rawItems.add(new PlanItem(
                    RAW,
                    item.tag().equals(tags.get(KEEPER)) ? KEEPER : UNUSED,
                    item.tag(),
                    requiredAssetId(item.rawAssetId(), "RAW"),
                    item.raw().path().toString(),
                    item.matchedFinalAssetId(),
                    item.matchedFinalPath() == null ? null : item.matchedFinalPath().toString(),
                    item.score(),
                    item.basis(),
                    albums.get(item.tag().equals(tags.get(KEEPER)) ? KEEPER : UNUSED),
                    requiredFileName(fileNames, item.rawAssetId())
            ));
        }

        List<PlanItem> finalItems = new ArrayList<>();
        for (FinalTagPlanItem item : session.finalTagPlan(
                tags.get(RAW_FOUND), tags.get(NO_RAW), tags.get(DUPLICATE))) {
            String decision = item.tag().equals(tags.get(RAW_FOUND)) ? RAW_FOUND
                    : item.tag().equals(tags.get(NO_RAW)) ? NO_RAW : DUPLICATE;
            finalItems.add(new PlanItem(
                    FINAL,
                    decision,
                    item.tag(),
                    requiredAssetId(item.finalAssetId(), "final image"),
                    item.finished().path().toString(),
                    item.matchedRawAssetId(),
                    item.matchedRawPath() == null ? null : item.matchedRawPath().toString(),
                    item.score(),
                    item.basis(),
                    albums.get(decision),
                    decision.equals(DUPLICATE) ? null : requiredFileName(fileNames, item.finalAssetId())
            ));
        }

        sort(rawItems);
        sort(finalItems);
        String fingerprint = fingerprint(session.createdAt(), tags, albums, rawItems, finalItems);
        return new ImmutableTagPlan(UUID.randomUUID().toString(), Instant.now(), session.createdAt(), fingerprint,
                tags, albums, rawItems, finalItems, null);
    }

    public ImmutableTagPlan withManifest(Path path) {
        return new ImmutableTagPlan(id, createdAt, sessionCreatedAt, fingerprint, decisionTags, decisionAlbums, rawItems, finalItems, path);
    }

    public boolean matches(ScanSession session, ImmichConfig config) {
        if (session == null || !sessionCreatedAt.equals(session.createdAt())) {
            return false;
        }
        try {
            ImmutableTagPlan current = fromSession(session, config);
            return MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8),
                    current.fingerprint.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant sessionCreatedAt() {
        return sessionCreatedAt;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public Map<String, String> decisionTags() {
        return decisionTags;
    }

    public Map<String, String> decisionAlbums() {
        return decisionAlbums;
    }

    public List<PlanItem> rawItems() {
        return rawItems;
    }

    public List<PlanItem> finalItems() {
        return finalItems;
    }

    public Path manifest() {
        return manifest;
    }

    public List<PlanItem> items() {
        List<PlanItem> items = new ArrayList<>(rawItems);
        items.addAll(finalItems);
        return List.copyOf(items);
    }

    public Map<String, Object> summary() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("createdAt", createdAt);
        values.put("fingerprint", fingerprint);
        values.put("manifest", manifest);
        values.put("rawItems", rawItems.size());
        values.put("finalItems", finalItems.size());
        values.put("filenameActions", items().stream().filter(item -> item.plannedFileName() != null).count());
        return values;
    }

    Map<String, Object> toJson() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", 2);
        values.put("id", id);
        values.put("createdAt", createdAt);
        values.put("sessionCreatedAt", sessionCreatedAt);
        values.put("fingerprint", fingerprint);
        values.put("decisionTags", decisionTags);
        values.put("decisionAlbums", decisionAlbums);
        values.put("rawItems", rawItems.stream().map(PlanItem::toJson).toList());
        values.put("finalItems", finalItems.stream().map(PlanItem::toJson).toList());
        values.put("manifest", manifest);
        return values;
    }

    static ImmutableTagPlan fromJson(Map<String, Object> values) {
        String id = string(values.get("id"));
        String fingerprint = string(values.get("fingerprint"));
        if (id.isBlank() || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Stored tag plan is missing its identity or fingerprint.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(values.get("decisionTags")).entrySet()) {
            tags.put(entry.getKey(), string(entry.getValue()));
        }
        Map<String, String> albums = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object(values.get("decisionAlbums")).entrySet()) {
            albums.put(entry.getKey(), string(entry.getValue()));
        }
        List<PlanItem> raw = array(values.get("rawItems")).stream().map(item -> PlanItem.fromJson(object(item))).toList();
        List<PlanItem> finals = array(values.get("finalItems")).stream().map(item -> PlanItem.fromJson(object(item))).toList();
        String manifest = string(values.get("manifest"));
        Instant createdAt = instant(values.get("createdAt"));
        Instant sessionCreatedAt = instant(values.get("sessionCreatedAt"));
        String calculated = albums.isEmpty()
                ? legacyFingerprint(sessionCreatedAt, tags, raw, finals)
                : fingerprint(sessionCreatedAt, tags, albums, raw, finals);
        if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.UTF_8), calculated.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Stored tag plan fingerprint does not match its contents.");
        }
        return new ImmutableTagPlan(id, createdAt, sessionCreatedAt,
                fingerprint, tags, albums, raw, finals, manifest.isBlank() ? null : Path.of(manifest));
    }

    private static Map<String, String> decisionTags(ImmichConfig config) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(KEEPER, config.keeperTag());
        tags.put(UNUSED, config.unusedTag());
        tags.put(RAW_FOUND, config.rawFoundTag());
        tags.put(NO_RAW, config.noRawTag());
        tags.put(DUPLICATE, config.duplicateTag());
        return tags;
    }

    private static Map<String, String> decisionAlbums(ImmichConfig config) {
        Map<String, String> configured = new LinkedHashMap<>(config.decisionAlbums());
        for (String decision : List.of(KEEPER, UNUSED, RAW_FOUND, NO_RAW, DUPLICATE)) {
            configured.putIfAbsent(decision, "");
        }
        return configured;
    }

    private static void validateDistinctTags(Map<String, String> tags, String account, String... decisions) {
        List<String> names = new ArrayList<>();
        for (String decision : decisions) {
            String tag = tags.get(decision);
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("The " + decision + " decision tag is blank.");
            }
            if (names.stream().anyMatch(existing -> existing.equalsIgnoreCase(tag))) {
                throw new IllegalArgumentException("Decision tag names must be distinct on the " + account + " account.");
            }
            names.add(tag);
        }
    }

    private static String requiredAssetId(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The dry-run contains a " + kind + " row without an Immich asset ID.");
        }
        return value;
    }

    private static String requiredFileName(Map<String, String> fileNames, String assetId) {
        String value = fileNames.get(assetId);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("The filename plan is missing asset " + assetId + ".");
        }
        return value;
    }

    private static void sort(List<PlanItem> items) {
        items.sort(Comparator.comparing(PlanItem::decision)
                .thenComparing(PlanItem::assetId)
                .thenComparing(PlanItem::tag));
    }

    private static String fingerprint(
            Instant sessionCreatedAt,
            Map<String, String> tags,
            Map<String, String> albums,
            List<PlanItem> raw,
            List<PlanItem> finals
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, sessionCreatedAt.toString());
        tags.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(canonical, entry.getKey());
                    append(canonical, entry.getValue());
                });
        albums.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(canonical, entry.getKey());
                    append(canonical, entry.getValue());
                });
        for (PlanItem item : raw) {
            item.appendCanonical(canonical);
        }
        for (PlanItem item : finals) {
            item.appendCanonical(canonical);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    /** Supports loading v1 plans only long enough to invalidate them safely. */
    private static String legacyFingerprint(
            Instant sessionCreatedAt,
            Map<String, String> tags,
            List<PlanItem> raw,
            List<PlanItem> finals
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, sessionCreatedAt.toString());
        tags.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    append(canonical, entry.getKey());
                    append(canonical, entry.getValue());
                });
        for (PlanItem item : raw) {
            item.appendLegacyCanonical(canonical);
        }
        for (PlanItem item : finals) {
            item.appendLegacyCanonical(canonical);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static void append(StringBuilder builder, String value) {
        String text = value == null ? "" : value;
        builder.append(text.length()).append(':').append(text).append('|');
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

    private static Instant instant(Object value) {
        try {
            return Instant.parse(string(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Stored tag plan contains an invalid timestamp.", ex);
        }
    }

    public record PlanItem(
            String account,
            String decision,
            String tag,
            String assetId,
            String path,
            String matchedAssetId,
            String matchedPath,
            int score,
            String basis,
            String album,
            String plannedFileName
    ) {
        Map<String, Object> toJson() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("account", account);
            values.put("decision", decision);
            values.put("tag", tag);
            values.put("assetId", assetId);
            values.put("path", path);
            values.put("matchedAssetId", matchedAssetId);
            values.put("matchedPath", matchedPath);
            values.put("score", score);
            values.put("basis", basis);
            values.put("album", album);
            values.put("plannedFileName", plannedFileName);
            return values;
        }

        static PlanItem fromJson(Map<String, Object> values) {
            return new PlanItem(string(values.get("account")), string(values.get("decision")),
                    string(values.get("tag")), requiredAssetId(string(values.get("assetId")), "plan"),
                    string(values.get("path")), nullable(values.get("matchedAssetId")), nullable(values.get("matchedPath")),
                    number(values.get("score")), string(values.get("basis")), nullable(values.get("album")),
                    nullable(values.get("plannedFileName")));
        }

        private void appendCanonical(StringBuilder builder) {
            append(builder, account);
            append(builder, decision);
            append(builder, tag);
            append(builder, assetId);
            append(builder, path);
            append(builder, matchedAssetId);
            append(builder, matchedPath);
            append(builder, Integer.toString(score));
            append(builder, basis);
            append(builder, album);
            append(builder, plannedFileName);
        }

        private void appendLegacyCanonical(StringBuilder builder) {
            append(builder, account);
            append(builder, decision);
            append(builder, tag);
            append(builder, assetId);
            append(builder, path);
            append(builder, matchedAssetId);
            append(builder, matchedPath);
            append(builder, Integer.toString(score));
            append(builder, basis);
        }

        private static int number(Object value) {
            return value instanceof Number number ? number.intValue() : 0;
        }

        private static String nullable(Object value) {
            String text = string(value);
            return text.isBlank() ? null : text;
        }
    }
}
