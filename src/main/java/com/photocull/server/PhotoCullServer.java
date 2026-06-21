package com.photocull.server;

import com.photocull.immich.ImmichConfig;
import com.photocull.immich.ImmichTagApplyResult;
import com.photocull.immich.ImmichUser;
import com.photocull.immich.ImmichWorkflow;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class PhotoCullServer {
    private static final int DEFAULT_PAGE_SIZE = 250;
    private static final int MAX_PAGE_SIZE = 1_000;
    private static final long PERSISTENCE_DELAY_MILLIS = 750;

    private final int port;
    private final Path configDir;
    private final ImmichConfig immichConfig;
    private final ImmichWorkflow immichWorkflow;
    private final String accessToken;
    private final SessionStore sessionStore;
    private volatile ScanSession session;
    private volatile ScanJob scanJob;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService persistenceExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Object persistenceLock = new Object();
    private ScheduledFuture<?> pendingPersistence;
    private HttpServer server;

    public PhotoCullServer(int port, Path configDir) {
        this(port, configDir, ImmichConfig.fromEnvironment());
    }

    public PhotoCullServer(int port, Path configDir, ImmichConfig immichConfig) {
        this(port, configDir, immichConfig, System.getenv("PCA_ACCESS_TOKEN"));
    }

    public PhotoCullServer(int port, Path configDir, ImmichConfig immichConfig, String accessToken) {
        this.port = port;
        this.configDir = configDir;
        this.immichConfig = immichConfig;
        this.immichWorkflow = new ImmichWorkflow(immichConfig);
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.sessionStore = new SessionStore(configDir);
    }

    public void start() throws IOException {
        Files.createDirectories(configDir);
        restorePersistedState();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/session", this::handleSession);
        server.createContext("/api/review", this::handleReview);
        server.createContext("/api/matches", this::handleMatches);
        server.createContext("/api/match/status", this::handleMatchStatus);
        server.createContext("/api/match/undo", this::handleMatchUndo);
        server.createContext("/api/tag-plan", this::handleTagPlan);
        server.createContext("/api/dry-run", this::handleDryRun);
        server.createContext("/api/immich/users", this::handleImmichUsers);
        server.createContext("/api/immich/scan", this::handleImmichScan);
        server.createContext("/api/immich/scan/status", this::handleImmichScanStatus);
        server.createContext("/api/immich/thumbnail", this::handleImmichThumbnail);
        server.createContext("/api/immich/apply-tags", this::handleImmichApplyTags);
        int requestThreads = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(Executors.newFixedThreadPool(requestThreads));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushPersistedState, "pca-state-flush"));
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", WebUi.html());
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        sendJson(exchange, statusJson());
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }

        if (session == null) {
            send(exchange, 409, "application/json", error("No completed scan session exists yet."));
            return;
        }
        sendJson(exchange, sessionJson(session));
    }

    private void handleReview(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        Map<String, String> parameters = query(exchange);
        boolean ascending = "asc".equalsIgnoreCase(parameters.getOrDefault("sort", "desc"));
        int limit = parsePageSize(parameters.get("limit"));
        List<MatchResult> matches = session.results();
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < matches.size(); index++) {
            if (matches.get(index).status() == MatchStatus.NEEDS_REVIEW) {
                indexes.add(index);
            }
        }
        Comparator<Integer> comparator = Comparator.comparingInt(index -> matches.get(index).score());
        if (!ascending) {
            comparator = comparator.reversed();
        }
        indexes.sort(comparator.thenComparingInt(Integer::intValue));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, indexes.size()); i++) {
            int index = indexes.get(i);
            rows.add(matchRow(index, matches.get(index)));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("session", sessionSummary(session));
        values.put("matches", rows);
        sendJson(exchange, Json.object(values));
    }

    private void handleMatches(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        Map<String, String> parameters = query(exchange);
        int offset = Math.max(0, parseInt(parameters.getOrDefault("offset", "0"), 0));
        int limit = parsePageSize(parameters.get("limit"));
        List<MatchResult> matches = session.results();
        int start = Math.min(offset, matches.size());
        int end = Math.min(start + limit, matches.size());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("offset", start);
        values.put("limit", limit);
        values.put("matchCount", matches.size());
        values.put("matches", matchRows(matches.subList(start, end), start));
        sendJson(exchange, Json.object(values));
    }

    private void handleMatchStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            Map<String, String> form = FormData.parse(FormData.body(exchange));
            int index = parseInt(require(form, "index"), -1);
            MatchStatus status = MatchStatus.valueOf(require(form, "status"));
            if (status != MatchStatus.ACCEPTED && status != MatchStatus.REJECTED) {
                throw new IllegalArgumentException("Review status must be ACCEPTED or REJECTED.");
            }
            MatchResult updated = session.updateStatus(index, status);
            schedulePersistState();
            sendJson(exchange, matchStatusJson(index, updated));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void handleTagPlan(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }
        sendJson(exchange, tagPlanJson(rawTagPlan(), finalTagPlan(), null));
    }

    private void handleDryRun(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            Path manifest = new DryRunManifestWriter().writeCsv(rawTagPlan(), finalTagPlan(), configDir);
            sendJson(exchange, tagPlanJson(rawTagPlan(), finalTagPlan(), manifest));
        } catch (Exception ex) {
            send(exchange, 500, "application/json", error(ex.getMessage()));
        }
    }

    private void handleImmichUsers(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }

        try {
            List<ImmichUser> users = immichWorkflow.users();
            sendJson(exchange, Json.object(Map.of("users", userRows(users))));
        } catch (Exception ex) {
            send(exchange, 500, "application/json", error(ex.getMessage()));
        }
    }

    private void handleImmichScan(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }

        if (scanJob != null && "RUNNING".equals(scanJob.json().get("state"))) {
            send(exchange, 409, "application/json", error("An Immich scan is already running."));
            return;
        }
        try {
            Map<String, String> form = FormData.parse(FormData.body(exchange));
            int autoAccept = parseThreshold(form.getOrDefault("autoAccept", "90"));
            int autoReject = parseThreshold(form.getOrDefault("autoReject", "50"));
            if (autoReject >= autoAccept) {
                throw new IllegalArgumentException("Auto-reject must be lower than auto-accept.");
            }
            ScanJob job = new ScanJob(this::schedulePersistState);
            scanJob = job;
            schedulePersistState();
            scanExecutor.submit(() -> {
                try {
                    ScanSession scanSession = immichWorkflow.scan(autoAccept, autoReject, job::progress);
                    job.progress("Finding duplicates...");
                    scanSession.duplicateCount();
                    scanSession.duplicateRawCount();
                    session = scanSession;
                    job.complete();
                } catch (Exception ex) {
                    job.fail(ex);
                }
            });
            send(exchange, 202, "application/json; charset=utf-8", Json.object(Map.of("job", job.json())));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void handleMatchUndo(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            MatchResult restored = session.undoLastReviewDecision();
            int index = session.results().indexOf(restored);
            schedulePersistState();
            sendJson(exchange, matchStatusJson(index, restored));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void handleImmichScanStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (scanJob == null) {
            send(exchange, 404, "application/json", error("No Immich scan job exists yet."));
            return;
        }
        sendJson(exchange, Json.object(Map.of("job", scanJob.json())));
    }

    private void handleImmichThumbnail(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }
        String assetId = query(exchange).get("assetId");
        if (assetId == null || assetId.isBlank()) {
            send(exchange, 400, "application/json", error("Missing assetId."));
            return;
        }
        boolean rawAsset = session.isRawAsset(assetId);
        boolean finalAsset = session.isFinalAsset(assetId);
        if (!rawAsset && !finalAsset) {
            send(exchange, 404, "application/json", error("Asset is not part of the active scan session."));
            return;
        }
        try {
            byte[] thumbnail = immichWorkflow.thumbnail(assetId, rawAsset);
            exchange.getResponseHeaders().set("Cache-Control", "private, max-age=3600");
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, thumbnail.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(thumbnail);
            }
        } catch (Exception ex) {
            send(exchange, 502, "application/json", error(ex.getMessage()));
        }
    }

    private void handleImmichApplyTags(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            ImmichTagApplyResult result = immichWorkflow.applyTags(session, configDir);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("keeperAssets", result.keeperAssets());
            values.put("unusedAssets", result.unusedAssets());
            values.put("rawFoundAssets", result.rawFoundAssets());
            values.put("noRawAssets", result.noRawAssets());
            values.put("duplicateAssets", result.duplicateAssets());
            values.put("keeperTagged", result.keeperTagged());
            values.put("unusedTagged", result.unusedTagged());
            values.put("rawFoundTagged", result.rawFoundTagged());
            values.put("noRawTagged", result.noRawTagged());
            values.put("duplicateTagged", result.duplicateTagged());
            values.put("manifest", result.manifest());
            sendJson(exchange, Json.object(values));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private String statusJson() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("port", port);
        values.put("hasSession", session != null);
        values.put("immich", immichStatus());
        values.put("accessProtected", accessProtected());
        if (scanJob != null) {
            values.put("scanJob", scanJob.json());
        }
        if (session != null) {
            values.put("session", sessionSummary(session));
        }
        return Json.object(values);
    }

    private String sessionJson(ScanSession scanSession) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("session", sessionSummary(scanSession));
        return Json.object(values);
    }

    private String matchStatusJson(int index, MatchResult result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("session", sessionSummary(session));
        values.put("match", matchRow(index, result));
        return Json.object(values);
    }

    private Map<String, Object> sessionSummary(ScanSession scanSession) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("createdAt", scanSession.createdAt());
        values.put("rawCount", scanSession.raws().size());
        values.put("finalCount", scanSession.finals().size());
        values.put("matchCount", scanSession.results().size());
        values.put("reviewCount", scanSession.reviewCount());
        values.put("rawReviewCount", scanSession.rawReviewCount());
        values.put("keeperCount", scanSession.keeperCount());
        values.put("unusedCount", scanSession.unusedCount());
        values.put("rawFoundCount", scanSession.rawFoundCount());
        values.put("noRawCount", scanSession.noRawCount());
        values.put("canUndoLastReviewDecision", scanSession.canUndoLastReviewDecision());
        values.put("duplicateCount", scanSession.duplicateCount());
        values.put("possibleDuplicateFinalCount", scanSession.possibleDuplicateFinalCount());
        values.put("duplicateRawCount", scanSession.duplicateRawCount());
        values.put("possibleDuplicateRawCount", scanSession.possibleDuplicateRawCount());
        values.put("autoAcceptThreshold", scanSession.threshold());
        values.put("autoRejectThreshold", scanSession.autoRejectThreshold());
        return values;
    }

    private List<Map<String, Object>> matchRows(List<MatchResult> matches) {
        return matchRows(matches, 0);
    }

    private List<Map<String, Object>> matchRows(List<MatchResult> matches, int indexOffset) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            rows.add(matchRow(i + indexOffset, matches.get(i)));
        }
        return rows;
    }

    private Map<String, Object> matchRow(int index, MatchResult result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("status", result.status().name());
        row.put("statusLabel", result.status().label());
        row.put("score", result.score());
        row.put("finishedAssetId", result.finished().immichAssetId());
        row.put("finishedPath", result.finished().path());
        row.put("rawAssetId", result.raw() == null ? null : result.raw().immichAssetId());
        row.put("rawPath", result.rawPathOrNull());
        row.put("reason", result.reason());
        return row;
    }

    private List<Map<String, Object>> userRows(List<ImmichUser> users) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ImmichUser user : users) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", user.id());
            row.put("name", user.name());
            row.put("email", user.email());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> immichStatus() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("configured", immichConfig.isConfigured());
        values.put("missing", immichConfig.missingFields());
        values.put("sharedApiKeyConfigured", immichConfig.sharedApiKeyConfigured());
        values.put("rawApiKeyConfigured", immichConfig.rawApiKeyConfigured());
        values.put("finalApiKeyConfigured", immichConfig.finalApiKeyConfigured());
        values.put("rawApiKeySource", immichConfig.rawApiKeySource());
        values.put("finalApiKeySource", immichConfig.finalApiKeySource());
        return values;
    }

    private String tagPlanJson(List<TagPlanItem> rawPlan, List<FinalTagPlanItem> finalPlan, Path manifest) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manifest", manifest);
        values.put("tagPlan", tagPlanRows(rawPlan));
        values.put("finalTagPlan", finalTagPlanRows(finalPlan));
        return Json.object(values);
    }

    private List<TagPlanItem> rawTagPlan() {
        return session.tagPlan(immichConfig.keeperTag(), immichConfig.unusedTag());
    }

    private List<FinalTagPlanItem> finalTagPlan() {
        return session.finalTagPlan(immichConfig.rawFoundTag(), immichConfig.noRawTag(), immichConfig.duplicateTag());
    }

    private List<Map<String, Object>> tagPlanRows(List<TagPlanItem> plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TagPlanItem item : plan) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", item.tag());
            row.put("rawAssetId", item.rawAssetId());
            row.put("rawPath", item.raw().path());
            row.put("matchedFinalAssetId", item.matchedFinalAssetId());
            row.put("matchedFinalPath", item.matchedFinalPath());
            row.put("score", item.score());
            row.put("basis", item.basis());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> finalTagPlanRows(List<FinalTagPlanItem> plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinalTagPlanItem item : plan) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tag", item.tag());
            row.put("finalAssetId", item.finalAssetId());
            row.put("finalPath", item.finished().path());
            row.put("matchedRawAssetId", item.matchedRawAssetId());
            row.put("matchedRawPath", item.matchedRawPath());
            row.put("score", item.score());
            row.put("basis", item.basis());
            rows.add(row);
        }
        return rows;
    }

    private String require(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parseThreshold(String value) {
        return Math.max(0, Math.min(100, parseInt(value, 90)));
    }

    private void restorePersistedState() {
        try {
            SessionStore.StoredState stored = sessionStore.load(this::schedulePersistState);
            session = stored.session();
            scanJob = stored.job();
            if (scanJob != null) {
                scanJob.interrupt();
            }
        } catch (Exception ex) {
            System.err.println("Ignoring unreadable persisted scan state: " + ex.getMessage());
        }
    }

    private int parsePageSize(String value) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, parseInt(value, DEFAULT_PAGE_SIZE)));
    }

    private void schedulePersistState() {
        synchronized (persistenceLock) {
            if (pendingPersistence != null) {
                pendingPersistence.cancel(false);
            }
            pendingPersistence = persistenceExecutor.schedule(this::persistStateNow,
                    PERSISTENCE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPersistedState() {
        synchronized (persistenceLock) {
            if (pendingPersistence != null) {
                pendingPersistence.cancel(false);
            }
        }
        persistStateNow();
        persistenceExecutor.shutdown();
    }

    private void persistStateNow() {
        try {
            sessionStore.save(session, scanJob);
        } catch (IOException ex) {
            System.err.println("Could not persist scan state: " + ex.getMessage());
        }
    }

    private Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private String error(String message) {
        return Json.object(Map.of("error", message == null ? "Unknown error" : message));
    }

    private boolean requireAccess(HttpExchange exchange) throws IOException {
        if (!accessProtected()) {
            return true;
        }
        String provided = exchange.getRequestHeaders().getFirst("X-PCA-Token");
        if (provided != null && MessageDigest.isEqual(
                accessToken.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        send(exchange, 401, "application/json", error("Access token required."));
        return false;
    }

    private boolean accessProtected() {
        return !accessToken.isBlank();
    }

    private void sendJson(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json; charset=utf-8", body);
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; connect-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'; "
                            + "script-src 'self' 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
