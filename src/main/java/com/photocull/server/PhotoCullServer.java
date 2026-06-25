package com.photocull.server;

import com.photocull.immich.ImmichConfig;
import com.photocull.immich.ImmichPermissionReport;
import com.photocull.immich.ImmichTagApplyResult;
import com.photocull.immich.ImmichUser;
import com.photocull.immich.ImmichWorkflow;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchScoreDetail;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import java.util.UUID;

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
    private final PlanStore planStore;
    private final HistoryStore historyStore;
    private volatile ScanSession session;
    private volatile ScanJob scanJob;
    private volatile ImmutableTagPlan activePlan;
    private volatile PlanApplyOperation activeOperation;
    private volatile ImmichPermissionReport permissionReport;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService operationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stateRestoreExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService persistenceExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Object persistenceLock = new Object();
    private final Object applyLock = new Object();
    private ScheduledFuture<?> pendingPersistence;
    private volatile boolean tagApplicationInProgress;
    private volatile OperationJob operationJob;
    private volatile boolean stateRestoreInProgress = true;
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
        this.planStore = new PlanStore(configDir);
        this.historyStore = new HistoryStore(configDir);
    }

    public void start() throws IOException {
        Files.createDirectories(configDir);
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", logged(this::handleIndex));
        server.createContext("/api/status", logged(this::handleStatus));
        server.createContext("/api/session", logged(this::handleSession));
        server.createContext("/api/review", logged(this::handleReview));
        server.createContext("/api/matches", logged(this::handleMatches));
        server.createContext("/api/history", logged(this::handleHistory));
        server.createContext("/api/cache/clear", logged(this::handleClearCache));
        server.createContext("/api/match/status", logged(this::handleMatchStatus));
        server.createContext("/api/match/undo", logged(this::handleMatchUndo));
        server.createContext("/api/tag-plan", logged(this::handleTagPlan));
        server.createContext("/api/dry-run", logged(this::handleDryRun));
        server.createContext("/api/operation/status", logged(this::handleOperationStatus));
        server.createContext("/api/immich/users", logged(this::handleImmichUsers));
        server.createContext("/api/immich/scan", logged(this::handleImmichScan));
        server.createContext("/api/immich/scan/status", logged(this::handleImmichScanStatus));
        server.createContext("/api/immich/thumbnail", logged(this::handleImmichThumbnail));
        server.createContext("/api/immich/permissions", logged(this::handleImmichPermissions));
        server.createContext("/api/immich/apply-tags", logged(this::handleImmichApplyTags));
        int requestThreads = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(Executors.newFixedThreadPool(requestThreads));
        server.start();
        AppLog.info("http.server.started", Map.of("port", port, "requestThreads", requestThreads));
        logHealth();
        persistenceExecutor.scheduleAtFixedRate(this::logHealth, 15, 15, TimeUnit.MINUTES);
        stateRestoreExecutor.submit(() -> {
            try {
                restorePersistedState();
            } finally {
                stateRestoreExecutor.shutdown();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AppLog.info("application.shutdown", Map.of("reason", "JVM shutdown hook"));
            flushPersistedState();
        }, "pca-state-flush"));
    }

    private HttpHandler logged(HttpHandler delegate) {
        return exchange -> {
            exchange.setAttribute("pca.requestId", UUID.randomUUID().toString());
            exchange.setAttribute("pca.startedAtNanos", System.nanoTime());
            try {
                delegate.handle(exchange);
            } catch (Exception ex) {
                AppLog.error("http.request.unhandled", requestFields(exchange), ex);
                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    send(exchange, 500, "application/json", error("Unexpected server error."));
                }
            }
        };
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
        if (!requireRestoredState(exchange)) {
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

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        try {
            Map<String, String> parameters = query(exchange);
            int limit = parsePageSize(parameters.get("limit"));
            String assetId = parameters.get("assetId");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("events", historyStore.list(limit, assetId));
            sendJson(exchange, Json.object(values));
        } catch (Exception ex) {
            send(exchange, 500, "application/json", error(ex.getMessage()));
        }
    }

    private void handleClearCache(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (!requireRestoredState(exchange)) {
            return;
        }

        try {
            Map<String, String> form = FormData.parse(FormData.body(exchange));
            if (!"true".equals(form.get("confirm"))) {
                throw new IllegalArgumentException("Confirm that you want to permanently clear saved review data.");
            }
        if (scanRunning()) {
                send(exchange, 409, "application/json", error("Wait for the active Immich scan to finish before clearing saved review data."));
                return;
            }
            if (operationRunning()) {
                send(exchange, 409, "application/json", error("Wait for the active operation to finish before clearing saved review data."));
                return;
            }
            if (tagApplicationInProgress) {
                send(exchange, 409, "application/json", error("Wait for the active tag application to finish before clearing saved review data."));
                return;
            }

            synchronized (applyLock) {
                if (tagApplicationInProgress) {
                    send(exchange, 409, "application/json", error("Wait for the active tag application to finish before clearing saved review data."));
                    return;
                }
                session = null;
                scanJob = null;
                permissionReport = null;
                activePlan = null;
                activeOperation = null;
                synchronized (persistenceLock) {
                    if (pendingPersistence != null) {
                        pendingPersistence.cancel(false);
                        pendingPersistence = null;
                    }
                }
                sessionStore.clear();
                historyStore.clear();
                planStore.clear();
            }
            AppLog.warn("state.cleared", Map.of("action", "saved_review_data"));
            sendJson(exchange, Json.object(Map.of("message", "Saved review data cleared.")));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void handleMatchStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (!requireRestoredState(exchange)) {
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
            MatchResult previous = session.results().get(index);
            MatchResult updated = session.updateStatus(index, status, form.get("rawAssetId"));
            invalidateActivePlan();
            persistStateImmediately();
            historyStore.recordReviewDecision(session, previous, updated);
            AppLog.info("review.decision", Map.of(
                    "sessionRevision", session.revision(), "status", updated.status().name(),
                    "score", updated.score(), "alternateRawSelected", previous.raw() != updated.raw()));
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
        ImmutableTagPlan approved = approvedPlanForCurrentSession();
        if (approved != null) {
            sendJson(exchange, tagPlanJson(approved));
        } else {
            sendJson(exchange, tagPlanJson(rawTagPlan(), finalTagPlan(), null, null));
        }
    }

    private void handleDryRun(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (!requireRestoredState(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            synchronized (applyLock) {
                if (scanRunning()) {
                    throw new IllegalStateException("Wait for the active Immich scan to finish before approving a dry-run plan.");
                }
                if (operationRunning()) {
                    throw new IllegalStateException("Another long operation is already running.");
                }
                ImmutableTagPlan existing = approvedPlanForCurrentSession();
                if (existing != null) {
                    sendJson(exchange, tagPlanJson(existing));
                    return;
                }
                ScanSession sessionSnapshot = session;
                OperationJob job = new OperationJob("DRY_RUN", "Preparing plan", "Preparing the immutable dry-run plan...");
                operationJob = job;
                operationExecutor.submit(() -> runDryRun(job, sessionSnapshot));
                send(exchange, 202, "application/json; charset=utf-8", Json.object(Map.of("job", job.json())));
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
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
            AppLog.info("immich.users.requested", Map.of());
            List<ImmichUser> users = immichWorkflow.users();
            AppLog.info("immich.users.received", Map.of("count", users.size()));
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

        if (!requireRestoredState(exchange)) {
            return;
        }
        if (scanJob != null && "RUNNING".equals(scanJob.json().get("state"))) {
            send(exchange, 409, "application/json", error("An Immich scan is already running."));
            return;
        }
        if (operationRunning()) {
            send(exchange, 409, "application/json", error("Wait for the active operation to finish before starting an Immich scan."));
            return;
        }
        try {
            Map<String, String> form = FormData.parse(FormData.body(exchange));
            int autoAccept = parseThreshold(form.getOrDefault("autoAccept", "90"));
            int autoReject = parseThreshold(form.getOrDefault("autoReject", "50"));
            if (autoReject >= autoAccept) {
                throw new IllegalArgumentException("Auto-reject must be lower than auto-accept.");
            }
            invalidateActivePlan();
            permissionReport = null;
            ScanJob job = new ScanJob(this::schedulePersistState);
            scanJob = job;
            schedulePersistState();
            AppLog.info("scan.started", Map.of("scanId", AppLog.shortId(job.id()), "autoAcceptThreshold", autoAccept,
                    "autoRejectThreshold", autoReject));
            scanExecutor.submit(() -> {
                long startedAt = System.nanoTime();
                try {
                    ScanSession scanSession = immichWorkflow.scan(autoAccept, autoReject, job::progress);
                    job.progress("Finding duplicates...");
                    scanSession.duplicateCount();
                    scanSession.duplicateRawCount();
                    job.progress("Writing durable decision history...");
                    historyStore.recordScanCompleted(scanSession);
                    session = scanSession;
                    job.complete();
                    AppLog.info("scan.completed", Map.of("scanId", AppLog.shortId(job.id()),
                            "rawCount", scanSession.raws().size(), "finalCount", scanSession.finals().size(),
                            "matchCount", scanSession.results().size(), "reviewCount", scanSession.reviewCount(),
                            "duplicateFinalCount", scanSession.duplicateCount(), "duplicateRawCount", scanSession.duplicateRawCount(),
                            "durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
                } catch (Exception ex) {
                    job.fail(ex);
                    AppLog.error("scan.failed", Map.of("scanId", AppLog.shortId(job.id()),
                            "durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)), ex);
                }
            });
            send(exchange, 202, "application/json; charset=utf-8", Json.object(Map.of("job", job.json())));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void handleOperationStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (operationJob == null) {
            send(exchange, 404, "application/json", error("No long-running operation exists yet."));
            return;
        }
        sendJson(exchange, Json.object(Map.of("job", operationJob.json())));
    }

    private void runDryRun(OperationJob job, ScanSession sessionSnapshot) {
        try {
            int assetCount = sessionSnapshot.raws().size() + sessionSnapshot.finals().size();
            long sessionRevision = sessionSnapshot.revision();
            job.progress("Building plan", "Building decisions for " + assetCount + " scanned assets...", 0, assetCount + 2);
            ImmutableTagPlan plan = ImmutableTagPlan.fromSession(sessionSnapshot, immichConfig);
            job.progress("Writing plan", "Writing the immutable plan and CSV manifest...", assetCount, assetCount + 2);
            if (session != sessionSnapshot || session.revision() != sessionRevision) {
                throw new IllegalStateException("Review decisions changed while the dry-run plan was being prepared. Approve it again.");
            }
            activePlan = planStore.freeze(plan);
            activeOperation = null;
            job.progress("Recording approval", "Saving the approved-plan audit record...", assetCount + 1, assetCount + 2);
            historyStore.recordPlanApproved(sessionSnapshot, activePlan);
            job.complete("Dry-run plan approved: " + activePlan.rawItems().size() + " RAW and "
                    + activePlan.finalItems().size() + " final-image decisions.");
            AppLog.info("plan.approved", Map.of("planId", AppLog.shortId(activePlan.id()),
                    "fingerprint", AppLog.shortId(activePlan.fingerprint()), "rawItems", activePlan.rawItems().size(),
                    "finalItems", activePlan.finalItems().size()));
        } catch (Exception ex) {
            job.fail(ex);
            AppLog.error("plan.approval_failed", Map.of("operationId", AppLog.shortId(job.id())), ex);
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
        if (!requireRestoredState(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        try {
            Integer lastIndex = session.lastReviewDecisionIndex();
            MatchResult previous = lastIndex == null ? null : session.results().get(lastIndex);
            MatchResult restored = session.undoLastReviewDecision();
            int index = session.results().indexOf(restored);
            invalidateActivePlan();
            persistStateImmediately();
            if (previous != null) {
                historyStore.recordReviewUndo(session, previous, restored);
            }
            AppLog.info("review.undone", Map.of("sessionRevision", session.revision(), "index", index));
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
        if (!requireRestoredState(exchange)) {
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
            logHttpResponse(exchange, 200, thumbnail.length, true);
        } catch (Exception ex) {
            AppLog.error("thumbnail.fetch_failed", Map.of("assetId", AppLog.shortId(assetId), "account", rawAsset ? "raw" : "final"), ex);
            send(exchange, 502, "application/json", error(ex.getMessage()));
        }
    }

    private void handleImmichPermissions(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            send(exchange, 405, "application/json", error("Method not allowed"));
            return;
        }
        if (!requireAccess(exchange)) {
            return;
        }
        if (!requireRestoredState(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("Run a scan before testing Immich API-key permissions."));
            return;
        }
        try {
            synchronized (applyLock) {
                if (tagApplicationInProgress) {
                    throw new IllegalStateException("Wait for the approved plan application to finish before testing permissions.");
                }
                permissionReport = immichWorkflow.checkPermissions(session);
            }
            AppLog.info("permissions.checked", Map.of("rawFailures", permissionFailures(permissionReport.raw()),
                    "finalFailures", permissionFailures(permissionReport.finalAccount())));
            sendJson(exchange, Json.object(Map.of("permissions", permissionRows(permissionReport))));
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
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
        if (!requireRestoredState(exchange)) {
            return;
        }
        if (session == null) {
            send(exchange, 409, "application/json", error("No scan session exists yet."));
            return;
        }

        ImmutableTagPlan plan = null;
        try {
            Map<String, String> form = FormData.parse(FormData.body(exchange));
            String planId = require(form, "planId");
            plan = approvedPlanForCurrentSession();
            if (plan == null) {
                throw new IllegalStateException("Write a new dry-run plan after resolving reviews before applying Immich tags.");
            }
            if (!plan.id().equals(planId)) {
                throw new IllegalArgumentException("The requested plan is no longer the active approved plan. Refresh and confirm again.");
            }

            synchronized (applyLock) {
                if (operationRunning() || tagApplicationInProgress) {
                    throw new IllegalStateException("A tag application is already running.");
                }
                ImmutableTagPlan stillApproved = approvedPlanForCurrentSession();
                if (stillApproved == null || !stillApproved.id().equals(plan.id())
                        || !stillApproved.fingerprint().equals(plan.fingerprint())) {
                    throw new IllegalStateException("The approved plan changed before tag application could start. Refresh and confirm again.");
                }
                tagApplicationInProgress = true;
                OperationJob job = new OperationJob("TAG_APPLICATION", "Preparing tag application",
                        "Loading the resumable tag-application checkpoint...");
                operationJob = job;
                ImmutableTagPlan approvedSnapshot = plan;
                ScanSession sessionSnapshot = session;
                operationExecutor.submit(() -> runTagApplication(job, approvedSnapshot, sessionSnapshot));
                send(exchange, 202, "application/json; charset=utf-8", Json.object(Map.of("job", job.json())));
            }
        } catch (Exception ex) {
            send(exchange, 400, "application/json", error(ex.getMessage()));
        }
    }

    private void runTagApplication(OperationJob job, ImmutableTagPlan plan, ScanSession sessionSnapshot) {
        PlanApplyOperation operation = null;
        try {
            operation = planStore.loadOrCreateOperation(plan);
            activeOperation = operation;
            updateApplyProgress(job, operation);
            AppLog.info("plan.apply_started", Map.of("planId", AppLog.shortId(plan.id()),
                    "operationId", AppLog.shortId(operation.id()), "resuming", operation.steps().stream().anyMatch(step -> step.affectedAssets() > 0)));
            PlanApplyOperation checkpointOperation = operation;
            ImmichTagApplyResult result = immichWorkflow.applyTags(plan, operation, updated -> {
                try {
                    planStore.saveOperation(updated);
                    activeOperation = updated;
                    updateApplyProgress(job, updated);
                    AppLog.debug("plan.apply_checkpoint", Map.of("planId", AppLog.shortId(updated.planId()),
                            "operationId", AppLog.shortId(updated.id()), "state", updated.state().name()));
                } catch (IOException ex) {
                    throw new IllegalStateException("Could not checkpoint the tag application: " + ex.getMessage(), ex);
                }
            });
            try {
                historyStore.recordApplyResult(sessionSnapshot, plan, checkpointOperation, result);
            } catch (IOException historyFailure) {
                AppLog.error("history.apply_result_record_failed", Map.of("planId", AppLog.shortId(plan.id())), historyFailure);
            }
            job.complete("Tags and Albums applied successfully. " + checkpointOperation.steps().size() + " steps completed.");
            AppLog.info("plan.apply_completed", Map.of("planId", AppLog.shortId(plan.id()),
                    "operationId", AppLog.shortId(checkpointOperation.id()), "state", checkpointOperation.state().name()));
        } catch (Exception ex) {
            job.fail(ex);
            AppLog.error("plan.apply_failed", Map.of("planId", AppLog.shortId(plan.id()),
                    "operationId", operation == null ? "" : AppLog.shortId(operation.id())), ex);
            try {
                historyStore.recordApplyFailure(sessionSnapshot, plan, operation, ex);
            } catch (IOException historyFailure) {
                AppLog.error("history.apply_failure_record_failed", Map.of("planId", AppLog.shortId(plan.id())), historyFailure);
            }
        } finally {
            tagApplicationInProgress = false;
        }
    }

    private void updateApplyProgress(OperationJob job, PlanApplyOperation operation) {
        List<PlanApplyOperation.Step> steps = operation.steps();
        int total = steps.stream().mapToInt(step -> step.assetIds().size()).sum();
        int completed = steps.stream().mapToInt(step -> step.state() == PlanApplyOperation.StepState.COMPLETE
                ? step.assetIds().size() : step.affectedAssets()).sum();
        PlanApplyOperation.Step active = steps.stream()
                .filter(step -> step.state() == PlanApplyOperation.StepState.RUNNING).findFirst().orElse(null);
        String phase = active == null ? "Preparing tag application" : describeApplyStep(active);
        String message = active == null
                ? "Preparing " + steps.size() + " tag and Album steps..."
                : active.affectedAssets() + " of " + active.assetIds().size() + " assets acknowledged for this step.";
        job.progress(phase, message, completed, total);
    }

    private String describeApplyStep(PlanApplyOperation.Step step) {
        return (step.mutation() == PlanApplyOperation.Mutation.ADD ? "Adding" : "Removing") + " "
                + step.resource().name().toLowerCase() + " '" + step.tag() + "' on the "
                + step.account().toLowerCase() + " account";
    }

    private String statusJson() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("port", port);
        values.put("stateRestoring", stateRestoreInProgress);
        values.put("hasSession", session != null);
        values.put("immich", immichStatus());
        if (permissionReport != null) {
            values.put("permissions", permissionRows(permissionReport));
        }
        values.put("accessProtected", accessProtected());
        if (scanJob != null) {
            values.put("scanJob", scanJob.json());
        }
        if (operationJob != null) {
            values.put("operationJob", operationJob.json());
        }
        if (session != null) {
            values.put("session", sessionSummary(session));
        }
        ImmutableTagPlan approved = approvedPlanForCurrentSession();
        if (approved != null) {
            values.put("activePlan", planSummary(approved));
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
        if (scanSession == session) {
            ImmutableTagPlan approved = approvedPlanForCurrentSession();
            if (approved != null) {
                values.put("activePlan", planSummary(approved));
            }
        }
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
        row.put("finishedMetadata", comparisonMetadata(result.finished()));
        row.put("rawAssetId", result.raw() == null ? null : result.raw().immichAssetId());
        row.put("rawPath", result.rawPathOrNull());
        row.put("rawMetadata", result.raw() == null ? null : comparisonMetadata(result.raw()));
        row.put("reason", result.reason());
        row.put("scoreDetails", scoreDetails(result.scoreDetails()));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (com.photocull.matcher.MatchCandidate candidate : result.candidates()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rawAssetId", candidate.raw().immichAssetId());
            value.put("rawPath", candidate.raw().path());
            value.put("rawMetadata", comparisonMetadata(candidate.raw()));
            value.put("score", candidate.score());
            value.put("reason", candidate.reason());
            value.put("scoreDetails", scoreDetails(candidate.scoreDetails()));
            candidates.add(value);
        }
        row.put("candidates", candidates);
        return row;
    }

    private List<Map<String, Object>> scoreDetails(List<MatchScoreDetail> details) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MatchScoreDetail detail : details) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", detail.key());
            row.put("label", detail.label());
            row.put("weight", detail.weight());
            row.put("points", detail.points());
            row.put("note", detail.note());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> comparisonMetadata(PhotoFile file) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("captureTimestamp", file.captureTime());
        metadata.put("cameraType", String.join(" ", List.of(file.make(), file.model()).stream()
                .filter(value -> value != null && !value.isBlank())
                .toList()));
        metadata.put("lensModel", file.lensModel());
        metadata.put("fNumber", file.fNumber());
        metadata.put("focalLength", file.focalLength());
        metadata.put("iso", file.iso());
        metadata.put("exposureTime", file.exposureTime());
        metadata.put("modifiedTimestamp", file.lastModified());
        return metadata;
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
        values.put("rawApiKeyConfigured", immichConfig.rawApiKeyConfigured());
        values.put("finalApiKeyConfigured", immichConfig.finalApiKeyConfigured());
        values.put("rawApiKeySource", immichConfig.rawApiKeySource());
        values.put("finalApiKeySource", immichConfig.finalApiKeySource());
        return values;
    }

    private String tagPlanJson(List<TagPlanItem> rawPlan, List<FinalTagPlanItem> finalPlan, Path manifest, ImmutableTagPlan plan) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manifest", manifest);
        values.put("tagPlan", tagPlanRows(rawPlan));
        values.put("finalTagPlan", finalTagPlanRows(finalPlan));
        values.put("plan", plan == null ? null : planSummary(plan));
        return Json.object(values);
    }

    private String tagPlanJson(ImmutableTagPlan plan) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("manifest", plan.manifest());
        values.put("tagPlan", plan.rawItems().stream().map(this::tagPlanRow).toList());
        values.put("finalTagPlan", plan.finalItems().stream().map(this::finalTagPlanRow).toList());
        values.put("plan", planSummary(plan));
        return Json.object(values);
    }

    private List<TagPlanItem> rawTagPlan() {
        return session.tagPlan(immichConfig.keeperTag(), immichConfig.unusedTag(), immichConfig.finalNotFoundTag());
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

    private Map<String, Object> tagPlanRow(ImmutableTagPlan.PlanItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", item.tag());
        row.put("rawAssetId", item.assetId());
        row.put("rawPath", item.path());
        row.put("matchedFinalAssetId", item.matchedAssetId());
        row.put("matchedFinalPath", item.matchedPath());
        row.put("score", item.score());
        row.put("basis", item.basis());
        row.put("album", item.album());
        row.put("plannedFileName", item.plannedFileName());
        return row;
    }

    private Map<String, Object> finalTagPlanRow(ImmutableTagPlan.PlanItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", item.tag());
        row.put("finalAssetId", item.assetId());
        row.put("finalPath", item.path());
        row.put("matchedRawAssetId", item.matchedAssetId());
        row.put("matchedRawPath", item.matchedPath());
        row.put("score", item.score());
        row.put("basis", item.basis());
        row.put("album", item.album());
        row.put("plannedFileName", item.plannedFileName());
        return row;
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
        AppLog.info("state.restore_started", Map.of("configDirectory", configDir.toString()));
        try {
            try {
                SessionStore.StoredState stored = sessionStore.load(this::schedulePersistState);
                session = stored.session();
                scanJob = stored.job();
                if (scanJob != null) {
                    scanJob.interrupt();
                    AppLog.warn("scan.interrupted_by_restart", Map.of("scanId", AppLog.shortId(scanJob.id())));
                }
                AppLog.info("state.session_restored", Map.of("hasSession", session != null, "hasScanJob", scanJob != null));
            } catch (Exception ex) {
                AppLog.error("state.session_restore_failed", Map.of("action", "ignoring unreadable persisted scan state"), ex);
            }
            try {
                activePlan = planStore.loadActive().orElse(null);
                if (activePlan != null && !activePlan.matches(session, immichConfig)) {
                    AppLog.warn("plan.invalidated_on_restore", Map.of("planId", AppLog.shortId(activePlan.id())));
                    invalidateActivePlan();
                } else if (activePlan != null) {
                    activeOperation = planStore.loadOperation(activePlan).orElse(null);
                }
                AppLog.info("state.plan_restored", Map.of("hasPlan", activePlan != null, "hasOperation", activeOperation != null));
            } catch (Exception ex) {
                activePlan = null;
                activeOperation = null;
                AppLog.error("state.plan_restore_failed", Map.of("action", "ignoring unreadable persisted tag plan state"), ex);
            }
        } finally {
            stateRestoreInProgress = false;
            AppLog.info("state.restore_completed", Map.of("hasSession", session != null, "hasPlan", activePlan != null));
        }
    }

    private ImmutableTagPlan approvedPlanForCurrentSession() {
        return session == null ? null : activePlan;
    }

    private void invalidateActivePlan() throws IOException {
        if (activePlan != null) {
            AppLog.info("plan.invalidated", Map.of("planId", AppLog.shortId(activePlan.id())));
        }
        activePlan = null;
        activeOperation = null;
        planStore.clearActive();
    }

    private Map<String, Object> planSummary(ImmutableTagPlan plan) {
        Map<String, Object> values = new LinkedHashMap<>(plan.summary());
        PlanApplyOperation operation = activeOperation;
        if (operation != null && plan.id().equals(operation.planId())
                && plan.fingerprint().equals(operation.planFingerprint())) {
            values.put("operation", operation.summary());
        }
        return values;
    }

    private Map<String, Object> permissionRows(ImmichPermissionReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("checkedAt", report.checkedAt());
        values.put("raw", permissionAccountRows(report.raw()));
        values.put("final", permissionAccountRows(report.finalAccount()));
        return values;
    }

    private Map<String, Object> permissionAccountRows(ImmichPermissionReport.Account account) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", account.label());
        values.put("checks", account.checks().stream().map(check -> Map.of(
                "capability", check.capability(),
                "state", check.state().name(),
                "detail", check.detail())).toList());
        return values;
    }

    private long permissionFailures(ImmichPermissionReport.Account account) {
        return account.checks().stream().filter(check -> check.state() == ImmichPermissionReport.State.FAIL).count();
    }

    private String applyResultJson(ImmichTagApplyResult result, PlanApplyOperation operation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("keeperAssets", result.keeperAssets());
        values.put("unusedAssets", result.unusedAssets());
        values.put("finalNotFoundAssets", result.finalNotFoundAssets());
        values.put("rawFoundAssets", result.rawFoundAssets());
        values.put("noRawAssets", result.noRawAssets());
        values.put("duplicateAssets", result.duplicateAssets());
        values.put("keeperTagged", result.keeperTagged());
        values.put("unusedTagged", result.unusedTagged());
        values.put("finalNotFoundTagged", result.finalNotFoundTagged());
        values.put("rawFoundTagged", result.rawFoundTagged());
        values.put("noRawTagged", result.noRawTagged());
        values.put("duplicateTagged", result.duplicateTagged());
        values.put("manifest", result.manifest());
        values.put("operation", operation.summary());
        return Json.object(values);
    }

    private int parsePageSize(String value) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, parseInt(value, DEFAULT_PAGE_SIZE)));
    }

    private boolean scanRunning() {
        return scanJob != null && "RUNNING".equals(scanJob.json().get("state"));
    }

    private boolean operationRunning() {
        return operationJob != null && operationJob.running();
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

    /**
     * Review choices are user checkpoints, so acknowledge them only after the
     * state file has been updated instead of waiting for the normal debounce.
     */
    private void persistStateImmediately() throws IOException {
        synchronized (persistenceLock) {
            if (pendingPersistence != null) {
                pendingPersistence.cancel(false);
                pendingPersistence = null;
            }
        }
        sessionStore.save(session, scanJob);
        AppLog.debug("state.persisted", Map.of("mode", "immediate", "hasSession", session != null, "hasScanJob", scanJob != null));
    }

    private void flushPersistedState() {
        if (stateRestoreInProgress) {
            stateRestoreExecutor.shutdownNow();
            persistenceExecutor.shutdownNow();
            return;
        }
        synchronized (persistenceLock) {
            if (pendingPersistence != null) {
                pendingPersistence.cancel(false);
            }
        }
        persistStateNow();
        persistenceExecutor.shutdown();
        AppLog.info("state.flush_completed", Map.of());
    }

    private void persistStateNow() {
        try {
            sessionStore.save(session, scanJob);
            AppLog.debug("state.persisted", Map.of("mode", "scheduled", "hasSession", session != null, "hasScanJob", scanJob != null));
        } catch (IOException ex) {
            AppLog.error("state.persist_failed", Map.of("hasSession", session != null, "hasScanJob", scanJob != null), ex);
        }
    }

    private void logHealth() {
        try {
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
            health.put("heapMaxBytes", runtime.maxMemory());
            health.put("threadCount", Thread.activeCount());
            health.put("configFreeBytes", Files.getFileStore(configDir).getUsableSpace());
            health.put("configTotalBytes", Files.getFileStore(configDir).getTotalSpace());
            health.put("scanRunning", scanRunning());
            health.put("tagApplicationInProgress", tagApplicationInProgress);
            AppLog.info("application.health", health);
        } catch (IOException ex) {
            AppLog.error("application.health_failed", Map.of("configDirectory", configDir.toString()), ex);
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
        AppLog.warn("security.authentication_failed", requestFields(exchange));
        return false;
    }

    private boolean requireRestoredState(HttpExchange exchange) throws IOException {
        if (!stateRestoreInProgress) {
            return true;
        }
        send(exchange, 503, "application/json", error("Restoring saved session data. Try again in a moment."));
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
        logHttpResponse(exchange, status, bytes.length, false);
    }

    private Map<String, Object> requestFields(HttpExchange exchange) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("requestId", AppLog.shortId(String.valueOf(exchange.getAttribute("pca.requestId"))));
        values.put("method", exchange.getRequestMethod());
        values.put("path", exchange.getRequestURI().getPath());
        values.put("remoteAddress", exchange.getRemoteAddress() == null || exchange.getRemoteAddress().getAddress() == null
                ? "" : exchange.getRemoteAddress().getAddress().getHostAddress());
        return values;
    }

    private void logHttpResponse(HttpExchange exchange, int status, int responseBytes, boolean thumbnail) {
        Map<String, Object> values = requestFields(exchange);
        Object startedAt = exchange.getAttribute("pca.startedAtNanos");
        if (startedAt instanceof Long started) {
            values.put("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
        values.put("status", status);
        values.put("responseBytes", responseBytes);
        if (thumbnail && status < 400) {
            AppLog.debug("http.thumbnail", values);
        } else if (status >= 500) {
            AppLog.error("http.request", values, null);
        } else if (status >= 400) {
            AppLog.warn("http.request", values);
        } else {
            AppLog.info("http.request", values);
        }
    }
}
