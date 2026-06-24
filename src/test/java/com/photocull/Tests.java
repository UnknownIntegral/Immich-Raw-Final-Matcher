package com.photocull;

import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.MatchCandidate;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;
import com.photocull.immich.ImmichApi;
import com.photocull.immich.ImmichAlbum;
import com.photocull.immich.ImmichAsset;
import com.photocull.immich.ImmichClient;
import com.photocull.immich.ImmichConfig;
import com.photocull.immich.ImmichPermissionReport;
import com.photocull.immich.ImmichTag;
import com.photocull.immich.ImmichUser;
import com.photocull.immich.ImmichWorkflow;
import com.photocull.server.FinalTagPlanItem;
import com.photocull.server.ImmutableTagPlan;
import com.photocull.server.HistoryStore;
import com.photocull.server.PlanApplyOperation;
import com.photocull.server.Json;
import com.photocull.server.PhotoCullServer;
import com.photocull.server.ScanSession;
import com.photocull.server.TagPlanItem;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class Tests {
    private Tests() {
    }

    public static void main(String[] args) throws Exception {
        parsesJsonObjects();
        reconcilesAbsentTagAndAlbumMemberships();
        normalizesImmichApiUrls();
        usesResilientImmichMutationDefaults();
        buildsImmichPhotoFiles();
        createsAssetIdTagPlans();
        separatesUnusedAndFinalNotFoundRawTags();
        createsFinalAccountTagPlans();
        tagsOnlyLowerFileSizeDuplicateFinals();
        autoRejectsLowScoresOutsideReviewQueue();
        rejectsFilenameMatchesWithConflictingCaptureTimes();
        autoAcceptsUniqueExactTimestamp();
        autoAcceptsExactTimestampWithOtherCandidates();
        updatesCachedSessionMetricsDuringReview();
        keepsUnusedAndFinalNotFoundDecisionsCorrectWithDateIndex();
        validatesApprovedPlanFromSessionRevision();
        acceptsReviewerSelectedAlternativeCandidate();
        recordsDurableDecisionHistory();
        undoesLastReviewDecision();
        keepsSessionAndReviewUpdatesSmall();
        providesReviewComparisonMetadata();
        separatesExactAndPossibleDuplicates();
        flagsFinalsWithMultipleStrongRawCandidates();
        requiresAccountSpecificImmichApiKeys();
        scansRawAndFinalAssetsWithSeparateClients();
        appliesTagsWithSideSpecificClients();
        plansDateOnlySharedBasenamesAndAlbums();
        testsPermissionsForEachAccountSpecificKey();
        freezesExactPlanBeforeApplying();
        reconcilesStaleDecisionTags();
        omitsApiKeysFromStatusJson();
        reportsStateRestoreProgress();
    }

    private static void parsesJsonObjects() {
        Map<String, Object> parsed = Json.parseObject("{\"assets\":{\"items\":[{\"id\":\"a1\",\"ok\":true}],\"nextPage\":null}}");
        Map<?, ?> assets = (Map<?, ?>) parsed.get("assets");
        List<?> items = (List<?>) assets.get("items");
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("a1", first.get("id"), "json id");
        assertEquals(Boolean.TRUE, first.get("ok"), "json bool");
        assertEquals(null, assets.get("nextPage"), "json null");
    }

    private static void reconcilesAbsentTagAndAlbumMemberships() throws Exception {
        Method countBulkResults = ImmichClient.class.getDeclaredMethod(
                "countBulkResults", String.class, int.class, boolean.class);
        countBulkResults.setAccessible(true);

        String missingMemberships = "["
                + "{\"id\":\"raw-1\",\"success\":false,\"error\":\"not_found\"},"
                + "{\"id\":\"raw-2\",\"success\":false,\"error\":\"not_found\"}]";
        assertEquals(2, countBulkResults.invoke(null, missingMemberships, 2, true),
                "removing an absent membership is already reconciled");
        assertEquals(0, countBulkResults.invoke(null, missingMemberships, 2, false),
                "adding still rejects an absent asset");

        String deniedMembership = "[{\"id\":\"raw-1\",\"success\":false,\"error\":\"no_permission\"}]";
        assertEquals(0, countBulkResults.invoke(null, deniedMembership, 1, true),
                "a missing permission remains a failure");
    }

    private static void normalizesImmichApiUrls() {
        ImmichConfig serverRoot = configForUrl("http://10.10.10.10:8084/");
        ImmichConfig apiRoot = configForUrl("http://10.10.10.10:8084/api/");
        assertEquals("http://10.10.10.10:8084/api", serverRoot.apiUrl(), "server root adds Immich API prefix");
        assertEquals("http://10.10.10.10:8084/api", apiRoot.apiUrl(), "existing Immich API prefix is preserved");
    }

    private static void usesResilientImmichMutationDefaults() {
        ImmichConfig config = config("raw-key", "final-key");
        assertEquals(180, config.requestTimeoutSeconds(), "Immich request timeout default");
        assertEquals(100, config.mutationBatchSize(), "Immich mutation batch size default");
        assertEquals(3, config.requestRetryAttempts(), "Immich retry attempt default");
    }

    private static void buildsImmichPhotoFiles() {
        PhotoFile raw = PhotoFile.fromImmichAsset(
                "raw-asset",
                "raw-owner",
                "IMG_1234.CR3",
                "/upload/raw/IMG_1234.CR3",
                42,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T09:59:58Z"),
                "Canon",
                "R5"
        );
        assertEquals("raw-asset", raw.immichAssetId(), "asset id");
        assertEquals("raw-owner", raw.immichOwnerId(), "owner id");
        assertEquals("CR3", raw.extension(), "extension");
        assertEquals("img1234", raw.normalizedStem(), "normalized stem");
        assertEquals("1234", raw.trailingNumber(), "trailing number");
    }

    private static void createsAssetIdTagPlans() {
        PhotoFile rawKeeper = PhotoFile.fromImmichAsset(
                "raw-1",
                "raw-owner",
                "IMG_0001.CR3",
                "/upload/raw/IMG_0001.CR3",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );
        PhotoFile rawUnused = PhotoFile.fromImmichAsset(
                "raw-2",
                "raw-owner",
                "IMG_0002.CR3",
                "/upload/raw/IMG_0002.CR3",
                1,
                Instant.parse("2024-01-01T10:05:00Z"),
                Instant.parse("2024-01-01T10:05:00Z"),
                "",
                ""
        );
        PhotoFile finished = PhotoFile.fromImmichAsset(
                "final-1",
                "final-owner",
                "IMG_0001.jpg",
                "/upload/final/IMG_0001.jpg",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );

        List<MatchResult> matches = new MatchEngine().match(List.of(rawKeeper, rawUnused), List.of(finished), 90, ignored -> {
        });
        ScanSession session = new ScanSession(List.of(rawKeeper, rawUnused), List.of(finished), matches, 90);
        List<TagPlanItem> plan = session.tagPlan();

        TagPlanItem keeper = plan.stream().filter(item -> item.tag().equals("Keeper")).findFirst().orElseThrow();
        TagPlanItem unused = plan.stream().filter(item -> item.tag().equals("not used")).findFirst().orElseThrow();
        assertEquals("raw-1", keeper.rawAssetId(), "keeper raw asset");
        assertEquals("final-1", keeper.matchedFinalAssetId(), "keeper final asset");
        assertEquals("raw-2", unused.rawAssetId(), "unused raw asset");
        assertEquals(null, unused.matchedFinalAssetId(), "unused final asset");
    }

    private static void createsFinalAccountTagPlans() {
        PhotoFile raw = PhotoFile.fromImmichAsset(
                "raw-1",
                "raw-owner",
                "IMG_0001.CR3",
                "/upload/raw/IMG_0001.CR3",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );
        PhotoFile matchedFinal = PhotoFile.fromImmichAsset(
                "final-1",
                "final-owner",
                "IMG_0001.jpg",
                "/upload/final/IMG_0001.jpg",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );
        PhotoFile unmatchedFinal = PhotoFile.fromImmichAsset(
                "final-2",
                "final-owner",
                "EXPORT_ONLY.jpg",
                "/upload/final/EXPORT_ONLY.jpg",
                1,
                Instant.parse("2024-01-01T11:00:00Z"),
                Instant.parse("2024-01-01T11:00:00Z"),
                "",
                ""
        );

        List<MatchResult> matches = new MatchEngine().match(List.of(raw), List.of(matchedFinal, unmatchedFinal), 90, ignored -> {
        });
        ScanSession session = new ScanSession(List.of(raw), List.of(matchedFinal, unmatchedFinal), matches, 90);
        List<FinalTagPlanItem> plan = session.finalTagPlan();

        FinalTagPlanItem rawFound = plan.stream().filter(item -> item.tag().equals("RAW Found")).findFirst().orElseThrow();
        FinalTagPlanItem noRaw = plan.stream().filter(item -> item.tag().equals("No RAW")).findFirst().orElseThrow();
        assertEquals("final-1", rawFound.finalAssetId(), "raw found final asset");
        assertEquals("raw-1", rawFound.matchedRawAssetId(), "raw found raw asset");
        assertEquals("final-2", noRaw.finalAssetId(), "no raw final asset");
        assertEquals(null, noRaw.matchedRawAssetId(), "no raw matched asset");
    }

    private static void tagsOnlyLowerFileSizeDuplicateFinals() {
        PhotoFile raw = PhotoFile.fromImmichAsset(
                "raw-1",
                "raw-owner",
                "IMG_0001.CR3",
                "/upload/raw/IMG_0001.CR3",
                100,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );
        PhotoFile largeFinal = PhotoFile.fromImmichAsset(
                "final-large",
                "final-owner",
                "IMG_0001.jpg",
                "/upload/final/a/IMG_0001.jpg",
                5000,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                "",
                "same-final-content"
        );
        PhotoFile smallFinal = PhotoFile.fromImmichAsset(
                "final-small",
                "final-owner",
                "IMG_0001.jpg",
                "/upload/final/b/IMG_0001.jpg",
                2500,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                "",
                "same-final-content"
        );

        List<MatchResult> matches = new MatchEngine().match(List.of(raw), List.of(largeFinal, smallFinal), 90, ignored -> {
        });
        ScanSession session = new ScanSession(List.of(raw), List.of(largeFinal, smallFinal), matches, 90);
        List<FinalTagPlanItem> plan = session.finalTagPlan();

        long rawFound = plan.stream().filter(item -> item.tag().equals("RAW Found")).count();
        FinalTagPlanItem duplicate = plan.stream().filter(item -> item.tag().equals("duplicate")).findFirst().orElseThrow();
        assertEquals(2L, rawFound, "both duplicate finals can be RAW Found");
        assertEquals("final-small", duplicate.finalAssetId(), "smaller duplicate final asset");
        assertEquals(1L, session.duplicateCount(), "duplicate count");
    }

    private static void autoRejectsLowScoresOutsideReviewQueue() {
        PhotoFile raw = photo("raw-1", "raw-owner", "IMG_0001.CR3");
        PhotoFile finalImage = PhotoFile.fromImmichAsset(
                "final-1", "final-owner", "EXPORT_ONLY.jpg", "/upload/EXPORT_ONLY.jpg", 1,
                Instant.parse("2024-01-01T11:00:00Z"), Instant.parse("2024-01-01T11:00:00Z"), "", ""
        );
        List<MatchResult> matches = new MatchEngine().match(List.of(raw), List.of(finalImage), 95, 90, ignored -> {
        });
        ScanSession session = new ScanSession(List.of(raw), List.of(finalImage), matches, 95, 90);

        assertEquals(MatchStatus.AUTO_REJECTED, matches.get(0).status(), "zero-score result auto rejected");
        assertEquals(0L, session.reviewCount(), "auto rejection stays out of review");
        assertEquals(1L, session.unusedCount(), "auto-rejected RAW is unused");
    }

    private static void rejectsFilenameMatchesWithConflictingCaptureTimes() {
        Instant rawTime = Instant.parse("2025-05-12T17:32:09Z");
        Instant finalTime = Instant.parse("2024-11-29T16:39:28Z");
        PhotoFile raw = PhotoFile.fromImmichAsset("raw-1", "raw-owner", "IMG_0001.CR3", "/raw/upload-id.CR3", 1,
                rawTime, rawTime, "Canon", "EOS R6m2");
        PhotoFile finished = PhotoFile.fromImmichAsset("final-1", "final-owner", "IMG_0001.jpg", "/final/upload-id.jpg", 1,
                finalTime, finalTime, "Canon", "EOS R6m2");

        MatchResult match = new MatchEngine().match(List.of(raw), List.of(finished), 90, 50, ignored -> { }).get(0);

        assertEquals(49, match.score(), "conflicting capture metadata caps filename score");
        assertEquals(MatchStatus.AUTO_REJECTED, match.status(), "conflicting capture metadata stays out of review");
        assertTrue(match.reason().contains("capture timestamps differ by more than 5 minutes"), "conflict reason is visible");
    }

    private static void updatesCachedSessionMetricsDuringReview() {
        PhotoFile rawFirst = photo("raw-1", "raw-owner", "IMG_0001.CR3");
        PhotoFile rawSecond = photo("raw-2", "raw-owner", "IMG_0002.CR3");
        PhotoFile firstFinal = photo("final-1", "final-owner", "IMG_0001.jpg");
        PhotoFile secondFinal = photo("final-2", "final-owner", "IMG_0001-edit.jpg");
        PhotoFile noRawFinal = photo("final-3", "final-owner", "EXPORT_ONLY.jpg");
        ScanSession session = new ScanSession(
                List.of(rawFirst, rawSecond),
                List.of(firstFinal, secondFinal, noRawFinal),
                List.of(
                        new MatchResult(firstFinal, rawFirst, 80, "review", MatchStatus.NEEDS_REVIEW, null),
                        new MatchResult(secondFinal, rawFirst, 75, "review", MatchStatus.NEEDS_REVIEW, null),
                        new MatchResult(noRawFinal, null, 0, "no RAW", MatchStatus.AUTO_REJECTED, null)
                ),
                90,
                50
        );

        assertEquals(2L, session.reviewCount(), "initial review count");
        assertEquals(2L, session.rawReviewCount(), "initial raw review count");
        assertEquals(0L, session.keeperCount(), "initial keeper count");
        assertEquals(1L, session.unusedCount(), "initial unused count");
        assertEquals(0L, session.rawFoundCount(), "initial RAW found count");
        assertEquals(1L, session.noRawCount(), "initial no RAW count");

        session.updateStatus(0, MatchStatus.ACCEPTED);
        assertEquals(1L, session.reviewCount(), "review count after acceptance");
        assertEquals(1L, session.rawReviewCount(), "raw review count after acceptance");
        assertEquals(1L, session.keeperCount(), "keeper count after acceptance");
        assertEquals(1L, session.unusedCount(), "unused count after acceptance");
        assertEquals(1L, session.rawFoundCount(), "RAW found count after acceptance");
        assertEquals(1L, session.noRawCount(), "no RAW count after acceptance");

        session.updateStatus(1, MatchStatus.REJECTED);
        assertEquals(0L, session.reviewCount(), "review count after rejection");
        assertEquals(0L, session.rawReviewCount(), "raw review count after rejection");
        assertEquals(1L, session.keeperCount(), "keeper count after rejection");
        assertEquals(1L, session.unusedCount(), "unused count after rejection");
        assertEquals(1L, session.rawFoundCount(), "RAW found count after rejection");
        assertEquals(2L, session.noRawCount(), "no RAW count after rejection");
        assertEquals(1L, session.tagPlan().stream().filter(item -> item.tag().equals("not used")).count(), "tag plan unused count");
        assertEquals(1L, session.finalTagPlan().stream().filter(item -> item.tag().equals("RAW Found")).count(), "tag plan RAW found count");
    }

    private static void keepsUnusedAndFinalNotFoundDecisionsCorrectWithDateIndex() {
        Instant firstDay = Instant.parse("2024-01-01T10:00:00Z");
        Instant secondDay = Instant.parse("2024-01-02T10:00:00Z");
        PhotoFile sameDayRaw = PhotoFile.fromImmichAsset("raw-same-day", "raw-owner", "IMG_0001.CR3", "/raw/IMG_0001.CR3", 1,
                firstDay, firstDay, "", "");
        PhotoFile missingRaw = PhotoFile.fromImmichAsset("raw-missing", "raw-owner", "IMG_0002.CR3", "/raw/IMG_0002.CR3", 1,
                secondDay, secondDay, "", "");
        PhotoFile finalImage = PhotoFile.fromImmichAsset("final-1", "final-owner", "EXPORT.jpg", "/final/EXPORT.jpg", 1,
                firstDay, firstDay, "", "");
        ScanSession session = new ScanSession(
                List.of(sameDayRaw, missingRaw),
                List.of(finalImage),
                List.of(new MatchResult(finalImage, null, 0, "no RAW", MatchStatus.AUTO_REJECTED, null)),
                90,
                50
        );

        List<TagPlanItem> plan = session.tagPlan();
        assertEquals("not used", plan.stream().filter(item -> item.rawAssetId().equals("raw-same-day")).findFirst().orElseThrow().tag(),
                "same-day RAW is unused");
        assertEquals("Final not found", plan.stream().filter(item -> item.rawAssetId().equals("raw-missing")).findFirst().orElseThrow().tag(),
                "RAW with no final date is final-not-found");
    }

    private static void validatesApprovedPlanFromSessionRevision() {
        PhotoFile raw = photo("plan-raw", "raw-owner", "IMG_0001.CR3");
        PhotoFile finalImage = photo("plan-final", "final-owner", "IMG_0001.jpg");
        ScanSession session = new ScanSession(
                List.of(raw),
                List.of(finalImage),
                List.of(new MatchResult(finalImage, raw, 80, "review", MatchStatus.NEEDS_REVIEW, null)),
                90,
                50
        );
        session.updateStatus(0, MatchStatus.ACCEPTED);
        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config("raw-key", "final-key"));

        assertEquals(session.revision(), plan.sessionRevision(), "plan stores session revision");
        assertTrue(plan.matches(session, config("raw-key", "final-key")), "matching revision keeps approved plan valid");
        session.undoLastReviewDecision();
        assertEquals(false, plan.matches(session, config("raw-key", "final-key")), "changed revision invalidates approved plan");
    }

    private static void acceptsReviewerSelectedAlternativeCandidate() {
        PhotoFile firstRaw = photo("raw-1", "raw-owner", "IMG_0001.CR3");
        PhotoFile secondRaw = photo("raw-2", "raw-owner", "ALT_0001.CR3");
        PhotoFile finished = photo("final-1", "final-owner", "IMG_0001.jpg");
        MatchResult review = new MatchResult(
                finished,
                firstRaw,
                82,
                "first candidate",
                MatchStatus.NEEDS_REVIEW,
                null,
                List.of(
                        new MatchCandidate(firstRaw, 82, "first candidate"),
                        new MatchCandidate(secondRaw, 79, "second candidate")
                )
        );
        ScanSession session = new ScanSession(List.of(firstRaw, secondRaw), List.of(finished), List.of(review), 90, 50);

        MatchResult accepted = session.updateStatus(0, MatchStatus.ACCEPTED, "raw-2");

        assertEquals("raw-2", accepted.raw().immichAssetId(), "selected alternate RAW is accepted");
        assertEquals(79, accepted.score(), "selected alternate score is preserved");
        assertEquals(1L, session.tagPlan().stream()
                .filter(item -> item.tag().equals("Keeper") && item.rawAssetId().equals("raw-2")).count(),
                "alternate RAW becomes Keeper");
        assertEquals(1L, session.tagPlan().stream()
                .filter(item -> item.tag().equals("not used") && item.rawAssetId().equals("raw-1")).count(),
                "original suggestion becomes unused");
    }

    private static void recordsDurableDecisionHistory() throws IOException {
        String suffix = UUID.randomUUID().toString();
        PhotoFile firstRaw = photo("history-raw-first-" + suffix, "raw-owner", "IMG_0001.CR3");
        PhotoFile secondRaw = photo("history-raw-second-" + suffix, "raw-owner", "ALT_0001.CR3");
        PhotoFile finished = photo("history-final-" + suffix, "final-owner", "IMG_0001.jpg");
        MatchResult review = new MatchResult(
                finished, firstRaw, 82, "first candidate", MatchStatus.NEEDS_REVIEW, null,
                List.of(new MatchCandidate(firstRaw, 82, "first candidate"), new MatchCandidate(secondRaw, 79, "second candidate"))
        );
        ScanSession session = new ScanSession(List.of(firstRaw, secondRaw), List.of(finished), List.of(review), 90, 50);
        MatchResult accepted = session.updateStatus(0, MatchStatus.ACCEPTED, secondRaw.immichAssetId());
        HistoryStore store = new HistoryStore(Path.of("build", "test-history", suffix));

        store.recordReviewDecision(session, review, accepted);
        List<Map<String, Object>> events = store.list(10, secondRaw.immichAssetId());

        assertEquals(1, events.size(), "stored decision history event count");
        assertEquals("REVIEW_DECISION", events.get(0).get("eventType"), "stored decision history type");
        assertEquals(secondRaw.immichAssetId(), events.get(0).get("rawAssetId"), "stored selected RAW");
        assertEquals(firstRaw.immichAssetId(), events.get(0).get("previousRawAssetId"), "stored original RAW suggestion");
    }

    private static void autoAcceptsUniqueExactTimestamp() {
        Instant captureTime = Instant.parse("2024-01-01T10:00:00Z");
        PhotoFile raw = PhotoFile.fromImmichAsset("raw-1", "raw-owner", "BURST_A.CR3", "/raw/BURST_A.CR3", 1,
                captureTime, captureTime, "", "");
        PhotoFile finished = PhotoFile.fromImmichAsset("final-1", "final-owner", "EXPORT_B.jpg", "/final/EXPORT_B.jpg", 1,
                captureTime, captureTime, "", "");

        MatchResult match = new MatchEngine().match(List.of(raw), List.of(finished), 90, 50, ignored -> { }).get(0);
        assertEquals(100, match.score(), "exact timestamp score");
        assertEquals(MatchStatus.AUTO_ACCEPTED, match.status(), "unique exact timestamp is auto accepted");
    }

    private static void autoAcceptsExactTimestampWithOtherCandidates() {
        Instant captureTime = Instant.parse("2024-01-01T10:00:00Z");
        PhotoFile namedRaw = PhotoFile.fromImmichAsset("raw-named", "raw-owner", "IMG_0001.CR3", "/raw/IMG_0001.CR3", 1,
                captureTime, captureTime, "Canon", "R5");
        PhotoFile otherRaw = PhotoFile.fromImmichAsset("raw-other", "raw-owner", "COPY_0001.CR3", "/raw/COPY_0001.CR3", 1,
                captureTime, captureTime, "Canon", "R5");
        PhotoFile finished = PhotoFile.fromImmichAsset("final-1", "final-owner", "IMG_0001.jpg", "/final/IMG_0001.jpg", 1,
                captureTime, captureTime, "Canon", "R5");

        MatchResult match = new MatchEngine().match(List.of(namedRaw, otherRaw), List.of(finished), 90, 50, ignored -> { }).get(0);
        assertEquals(100, match.score(), "exact timestamp remains a perfect score with alternatives");
        assertEquals(MatchStatus.AUTO_ACCEPTED, match.status(), "exact timestamp does not require review");
        assertEquals("raw-named", match.raw().immichAssetId(), "stronger filename evidence breaks an exact-time tie");
    }

    private static void undoesLastReviewDecision() {
        PhotoFile raw = photo("raw-1", "raw-owner", "IMG_0001.CR3");
        PhotoFile finished = photo("final-1", "final-owner", "IMG_0001.jpg");
        ScanSession session = new ScanSession(
                List.of(raw), List.of(finished),
                List.of(new MatchResult(finished, raw, 80, "review", MatchStatus.NEEDS_REVIEW, null)),
                90, 50
        );

        session.updateStatus(0, MatchStatus.ACCEPTED);
        assertTrue(session.canUndoLastReviewDecision(), "acceptance can be undone");
        MatchResult restored = session.undoLastReviewDecision();
        assertEquals(MatchStatus.NEEDS_REVIEW, restored.status(), "undo returns item to review");
        assertEquals(1L, session.reviewCount(), "undo restores review count");
        assertEquals(0L, session.rawFoundCount(), "undo removes accepted match");
        assertEquals(0L, session.unusedCount(), "undo keeps a pending RAW out of the unused plan");
        assertEquals(false, session.canUndoLastReviewDecision(), "undo is one step");
    }

    private static void keepsSessionAndReviewUpdatesSmall() throws Exception {
        PhotoFile raw = photo("raw-1", "raw-owner", "IMG_0001.CR3");
        PhotoFile finished = photo("final-1", "final-owner", "IMG_0001.jpg");
        ScanSession session = new ScanSession(
                List.of(raw), List.of(finished),
                List.of(new MatchResult(finished, raw, 80, "review", MatchStatus.NEEDS_REVIEW, null)),
                90, 50
        );
        PhotoCullServer server = new PhotoCullServer(8356, Path.of("build", "test-config"), config("raw-key", "final-key"));
        Method sessionJson = PhotoCullServer.class.getDeclaredMethod("sessionJson", ScanSession.class);
        sessionJson.setAccessible(true);
        Map<String, Object> sessionPayload = Json.parseObject((String) sessionJson.invoke(server, session));

        assertTrue(sessionPayload.containsKey("session"), "session payload includes summary");
        assertTrue(!sessionPayload.containsKey("matches"), "session payload excludes all match rows");
        assertTrue(!sessionPayload.containsKey("tagPlan"), "session payload excludes full tag plan");
    }

    private static void providesReviewComparisonMetadata() throws Exception {
        Instant timestamp = Instant.parse("2024-01-01T10:00:00Z");
        PhotoFile raw = PhotoFile.fromImmichAsset("raw-1", "raw-owner", "IMG_0001.CR3", "/raw/IMG_0001.CR3", 12_345,
                timestamp, timestamp, "Canon", "R5");
        PhotoFile finished = PhotoFile.fromImmichAsset("final-1", "final-owner", "IMG_0001.jpg", "/final/IMG_0001.jpg", 6_789,
                timestamp, timestamp, "Canon", "R5");
        PhotoCullServer server = new PhotoCullServer(8356, Path.of("build", "test-config"), config("raw-key", "final-key"));
        Method matchRow = PhotoCullServer.class.getDeclaredMethod("matchRow", int.class, MatchResult.class);
        matchRow.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) matchRow.invoke(server, 0,
                new MatchResult(finished, raw, 100, "exact capture timestamp", MatchStatus.AUTO_ACCEPTED, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> finalMetadata = (Map<String, Object>) row.get("finishedMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> rawMetadata = (Map<String, Object>) row.get("rawMetadata");

        assertEquals(timestamp, finalMetadata.get("captureTimestamp"), "comparison includes final capture time");
        assertEquals("Canon R5", rawMetadata.get("cameraType"), "comparison includes RAW camera type");
        assertEquals(false, finalMetadata.containsKey("filename"), "comparison excludes filename");
        assertEquals(false, finalMetadata.containsKey("fileType"), "comparison excludes file type");
        assertEquals(false, rawMetadata.containsKey("fileSizeBytes"), "comparison excludes file size");
    }

    private static void separatesExactAndPossibleDuplicates() {
        PhotoFile exactFirst = photo("raw-1", "raw-owner", "IMG_0001.CR3", 200, "same-content");
        PhotoFile exactSecond = photo("raw-2", "raw-owner", "IMG_0002.CR3", 100, "same-content");
        PhotoFile possibleFirst = photo("raw-3", "raw-owner", "COPY_0003.CR3");
        PhotoFile possibleSecond = photo("raw-4", "raw-owner", "COPY_0003.CR3");
        ScanSession session = new ScanSession(
                List.of(exactFirst, exactSecond, possibleFirst, possibleSecond), List.of(), List.of(), 90, 50);

        assertEquals(1L, session.duplicateRawCount(), "exact RAW duplicate count");
        assertEquals(1L, session.possibleDuplicateRawCount(), "filename-only RAW duplicate count");
    }

    private static void flagsFinalsWithMultipleStrongRawCandidates() {
        PhotoFile firstRaw = PhotoFile.fromImmichAsset(
                "raw-1",
                "raw-owner",
                "IMG_0001.CR3",
                "/upload/raw/IMG_0001.CR3",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                ""
        );
        PhotoFile secondRaw = PhotoFile.fromImmichAsset(
                "raw-2",
                "raw-owner",
                "IMG_0001.CR3",
                "/upload/raw/second/IMG_0001.CR3",
                1,
                Instant.parse("2024-01-01T10:00:04Z"),
                Instant.parse("2024-01-01T10:00:04Z"),
                "",
                ""
        );
        PhotoFile finished = PhotoFile.fromImmichAsset(
                "final-1",
                "final-owner",
                "IMG_0001.jpg",
                "/upload/final/IMG_0001.jpg",
                1,
                Instant.parse("2024-01-01T10:00:03Z"),
                Instant.parse("2024-01-01T10:00:03Z"),
                "",
                ""
        );

        List<MatchResult> matches = new MatchEngine().match(List.of(firstRaw, secondRaw), List.of(finished), 90, ignored -> {
        });
        MatchResult result = matches.get(0);
        assertEquals(MatchStatus.NEEDS_REVIEW, result.status(), "ambiguous raw match status");
        assertTrue(result.reason().contains("multiple strong RAW candidates"), "ambiguous raw match reason");
        assertEquals(2, result.candidates().size(), "ambiguous match retains review candidates");
    }

    private static void requiresAccountSpecificImmichApiKeys() {
        ImmichConfig split = config("raw-key", "final-key");
        assertEquals("raw-key", split.effectiveRawApiKey(), "separate raw key");
        assertEquals("final-key", split.effectiveFinalApiKey(), "separate final key");
        assertEquals("RAW_IMMICH_API_KEY", split.rawApiKeySource(), "raw key source");
        assertEquals("FINAL_IMMICH_API_KEY", split.finalApiKeySource(), "final key source");

        ImmichConfig missingRaw = config("", "final-key");
        assertTrue(missingRaw.missingFields().contains("RAW_IMMICH_API_KEY"), "raw key missing");
        assertTrue(!missingRaw.missingFields().contains("FINAL_IMMICH_API_KEY"), "final key present");
    }

    private static void scansRawAndFinalAssetsWithSeparateClients() throws IOException, InterruptedException {
        RecordingImmichApi rawApi = new RecordingImmichApi(List.of(asset("raw-asset", "raw-user", "IMG_0001.CR3")));
        RecordingImmichApi finalApi = new RecordingImmichApi(List.of(asset("final-asset", "final-user", "IMG_0001.jpg")));
        ImmichWorkflow workflow = new ImmichWorkflow(config("raw-key", "final-key"), new RecordingImmichApi(), rawApi, finalApi);

        ScanSession session = workflow.scan(90, ignored -> {
        });

        assertEquals(List.of("raw-user"), rawApi.searchOwners, "raw search owner");
        assertEquals(List.of("final-user"), finalApi.searchOwners, "final search owner");
        assertEquals(1, session.raws().size(), "raw count from raw client");
        assertEquals(1, session.finals().size(), "final count from final client");
    }

    private static void appliesTagsWithSideSpecificClients() throws IOException, InterruptedException {
        RecordingImmichApi rawApi = new RecordingImmichApi();
        RecordingImmichApi finalApi = new RecordingImmichApi();
        ImmichWorkflow workflow = new ImmichWorkflow(config("raw-key", "final-key"), new RecordingImmichApi(), rawApi, finalApi);
        PhotoFile rawKeeper = photo("raw-keeper", "raw-user", "IMG_0001.CR3");
        PhotoFile rawUnused = photo("raw-unused", "raw-user", "IMG_0002.CR3");
        PhotoFile rawDuplicate = photo("raw-duplicate", "raw-user", "DUP_0004.CR3");
        PhotoFile finalMatched = photo("final-matched", "final-user", "IMG_0001.jpg");
        PhotoFile finalUnmatched = photo("final-unmatched", "final-user", "IMG_0003.jpg");
        PhotoFile finalDuplicateLarge = photo("final-large", "final-user", "DUP_0004.jpg", 5000, "same-final-content");
        PhotoFile finalDuplicateSmall = photo("final-small", "final-user", "DUP_0004.jpg", 2500, "same-final-content");
        List<MatchResult> matches = List.of(
                new MatchResult(finalMatched, rawKeeper, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null),
                new MatchResult(finalUnmatched, null, 0, "no RAW", MatchStatus.REJECTED, null),
                new MatchResult(finalDuplicateLarge, rawDuplicate, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null),
                new MatchResult(finalDuplicateSmall, rawDuplicate, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null)
        );
        ScanSession session = new ScanSession(
                List.of(rawKeeper, rawUnused, rawDuplicate),
                List.of(finalMatched, finalUnmatched, finalDuplicateLarge, finalDuplicateSmall),
                matches,
                90
        );

        workflow.applyTags(session, Path.of("build", "test-manifests"));

        assertEquals(true, rawApi.ensuredTags.contains("Keeper"), "raw client ensures Keeper");
        assertEquals(true, rawApi.ensuredTags.contains("not used"), "raw client ensures not used");
        assertEquals(false, rawApi.ensuredTags.contains("RAW Found"), "raw client does not ensure final tag");
        assertEquals(true, finalApi.ensuredTags.contains("RAW Found"), "final client ensures RAW Found");
        assertEquals(true, finalApi.ensuredTags.contains("No RAW"), "final client ensures No RAW");
        assertEquals(true, finalApi.ensuredTags.contains("duplicate"), "final client ensures duplicate");
        assertEquals(false, finalApi.ensuredTags.contains("Keeper"), "final client does not ensure raw tag");
        assertTrue(rawApi.taggedAssetIds.contains("raw-keeper"), "raw client tags keeper asset");
        assertTrue(rawApi.taggedAssetIds.contains("raw-unused"), "raw client tags unused asset");
        assertTrue(finalApi.taggedAssetIds.contains("final-matched"), "final client tags matched final");
        assertTrue(finalApi.taggedAssetIds.contains("final-unmatched"), "final client tags unmatched final");
        assertTrue(finalApi.taggedAssetIds.contains("final-small"), "final client tags duplicate final");
        assertTrue(rawApi.albumAssetIds.contains("raw-keeper"), "raw client adds Keeper to Album");
        assertTrue(finalApi.albumAssetIds.contains("final-matched"), "final client adds finished image to Album");
    }

    private static void plansDateOnlySharedBasenamesAndAlbums() {
        PhotoFile raw = PhotoFile.fromImmichAsset("raw-1", "raw-user", "IMG_0001.CR3", "/raw/IMG_0001.CR3", 1,
                Instant.parse("2024-06-22T16:00:00Z"), Instant.parse("2024-06-22T16:00:00Z"), "", "");
        PhotoFile matchedFinal = PhotoFile.fromImmichAsset("final-1", "final-user", "IMG_0001.jpg", "/final/IMG_0001.jpg", 1,
                Instant.parse("2024-06-22T16:01:00Z"), Instant.parse("2024-06-22T16:01:00Z"), "", "");
        PhotoFile unmatchedFinal = PhotoFile.fromImmichAsset("final-2", "final-user", "EXPORT.jpg", "/final/EXPORT.jpg", 1,
                Instant.parse("2024-06-22T16:02:00Z"), Instant.parse("2024-06-22T16:02:00Z"), "", "");
        ScanSession session = new ScanSession(List.of(raw), List.of(matchedFinal, unmatchedFinal), List.of(
                new MatchResult(matchedFinal, raw, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null),
                new MatchResult(unmatchedFinal, null, 0, "no RAW", MatchStatus.AUTO_REJECTED, null)), 90, 50);

        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config("raw-key", "final-key"));
        ImmutableTagPlan.PlanItem rawItem = plan.rawItems().get(0);
        ImmutableTagPlan.PlanItem firstFinal = plan.finalItems().stream()
                .filter(item -> item.assetId().equals("final-1")).findFirst().orElseThrow();
        ImmutableTagPlan.PlanItem secondFinal = plan.finalItems().stream()
                .filter(item -> item.assetId().equals("final-2")).findFirst().orElseThrow();

        assertEquals("2024-06-22-000001.CR3", rawItem.plannedFileName(), "raw planned name");
        assertEquals("2024-06-22-000001.jpg", firstFinal.plannedFileName(), "matched final planned name");
        assertEquals("2024-06-22-000002.jpg", secondFinal.plannedFileName(), "unmatched final planned name");
        assertEquals("PCA - Keeper RAWs", rawItem.album(), "Keeper Album");
        assertEquals("PCA - Finished", firstFinal.album(), "finished Album");
        assertTrue(PlanApplyOperation.create(plan).steps().stream()
                        .anyMatch(step -> step.resource() == PlanApplyOperation.Resource.ALBUM),
                "plan includes Album actions");
    }

    private static void testsPermissionsForEachAccountSpecificKey() {
        RecordingImmichApi rawApi = new RecordingImmichApi();
        RecordingImmichApi finalApi = new RecordingImmichApi();
        PhotoFile raw = photo("raw-permission", "raw-user", "IMG_0001.CR3");
        PhotoFile finished = photo("final-permission", "final-user", "IMG_0001.jpg");
        ScanSession session = new ScanSession(List.of(raw), List.of(finished), List.of(
                new MatchResult(finished, raw, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null)), 90, 50);
        ImmichPermissionReport report = new ImmichWorkflow(config("raw-key", "final-key"), new RecordingImmichApi(), rawApi, finalApi)
                .checkPermissions(session);

        assertEquals("RAW API key", report.raw().label(), "raw permission card");
        assertEquals("Final API key", report.finalAccount().label(), "final permission card");
        assertTrue(report.raw().checks().stream().anyMatch(check -> check.capability().equals("Tag write")
                        && check.state() == ImmichPermissionReport.State.PASS), "raw key tag write permission");
        assertTrue(report.finalAccount().checks().stream().anyMatch(check -> check.capability().equals("Album write")
                        && check.state() == ImmichPermissionReport.State.PASS), "final key Album write permission");
        assertTrue(report.raw().checks().stream().anyMatch(check -> check.capability().equals("Displayed filename update")
                        && check.state() == ImmichPermissionReport.State.UNSUPPORTED), "filename update safely unavailable");
        assertTrue(rawApi.taggedAssetIds.contains("raw-permission"), "raw API key runs its own write probe");
        assertTrue(finalApi.taggedAssetIds.contains("final-permission"), "final API key runs its own write probe");
    }

    private static void freezesExactPlanBeforeApplying() {
        PhotoFile raw = photo("raw-1", "raw-user", "IMG_0001.CR3");
        PhotoFile finished = photo("final-1", "final-user", "IMG_0001.jpg");
        ScanSession session = new ScanSession(
                List.of(raw), List.of(finished),
                List.of(new MatchResult(finished, raw, 95, "accepted", MatchStatus.ACCEPTED, null)),
                90, 50
        );

        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config("raw-key", "final-key"));
        PlanApplyOperation operation = PlanApplyOperation.create(plan);

        assertEquals(true, plan.matches(session, config("raw-key", "final-key")), "frozen plan matches source session");
        assertEquals(false, plan.matches(session, new ImmichConfig(
                "http://immich.local/api", "raw-key", "final-key", "raw-user", "final-user",
                "Different Keeper", "not used", "RAW Found", "No RAW", "duplicate", 1000, 10000
        )), "frozen plan rejects changed tag configuration");
        assertEquals("apply-" + plan.id(), operation.id(), "operation is deterministic for the plan");
        assertTrue(operation.steps().stream().anyMatch(step -> step.id().equals("remove-raw-keeper")),
                "operation removes app-managed raw tags before applying them");
        assertTrue(operation.steps().stream().anyMatch(step -> step.id().equals("add-final-raw-found")),
                "operation includes final application");
    }

    private static void separatesUnusedAndFinalNotFoundRawTags() {
        PhotoFile rawWithFinalDay = PhotoFile.fromImmichAsset(
                "raw-unused", "raw-owner", "IMG_0002.CR3", "/raw/IMG_0002.CR3", 1,
                Instant.parse("2024-01-01T11:00:00Z"), Instant.parse("2024-01-01T11:00:00Z"), "", "");
        PhotoFile rawWithoutFinalDay = PhotoFile.fromImmichAsset(
                "raw-final-not-found", "raw-owner", "IMG_0003.CR3", "/raw/IMG_0003.CR3", 1,
                Instant.parse("2024-01-02T11:00:00Z"), Instant.parse("2024-01-02T11:00:00Z"), "", "");
        PhotoFile finished = PhotoFile.fromImmichAsset(
                "final-1", "final-owner", "EXPORT.jpg", "/final/EXPORT.jpg", 1,
                Instant.parse("2024-01-01T12:00:00Z"), Instant.parse("2024-01-01T12:00:00Z"), "", "");
        ScanSession session = new ScanSession(
                List.of(rawWithFinalDay, rawWithoutFinalDay), List.of(finished),
                List.of(new MatchResult(finished, null, 0, "no RAW", MatchStatus.AUTO_REJECTED, null)), 90, 50);

        List<TagPlanItem> plan = session.tagPlan();
        assertEquals("not used", plan.stream().filter(item -> item.rawAssetId().equals("raw-unused"))
                .findFirst().orElseThrow().tag(), "RAW on a final-image date is not used");
        assertEquals("Final not found", plan.stream().filter(item -> item.rawAssetId().equals("raw-final-not-found"))
                .findFirst().orElseThrow().tag(), "RAW with no final-image date is Final not found");
    }

    private static void reconcilesStaleDecisionTags() throws IOException, InterruptedException {
        RecordingImmichApi rawApi = new RecordingImmichApi();
        RecordingImmichApi finalApi = new RecordingImmichApi();
        rawApi.visibleTags.addAll(List.of(
                new ImmichTag("raw-keeper", "Keeper", "Keeper"),
                new ImmichTag("raw-unused", "not used", "not used")
        ));
        finalApi.visibleTags.addAll(List.of(
                new ImmichTag("final-found", "RAW Found", "RAW Found"),
                new ImmichTag("final-none", "No RAW", "No RAW"),
                new ImmichTag("final-duplicate", "duplicate", "duplicate")
        ));
        ImmichWorkflow workflow = new ImmichWorkflow(config("raw-key", "final-key"), new RecordingImmichApi(), rawApi, finalApi);
        PhotoFile keeper = photo("raw-keeper", "raw-user", "IMG_0001.CR3");
        PhotoFile unused = photo("raw-unused", "raw-user", "IMG_0002.CR3");
        PhotoFile matched = photo("final-matched", "final-user", "IMG_0001.jpg");
        PhotoFile unmatched = photo("final-unmatched", "final-user", "EXPORT_ONLY.jpg");
        ScanSession session = new ScanSession(
                List.of(keeper, unused), List.of(matched, unmatched),
                List.of(
                        new MatchResult(matched, keeper, 100, "accepted", MatchStatus.AUTO_ACCEPTED, null),
                        new MatchResult(unmatched, null, 0, "no RAW", MatchStatus.AUTO_REJECTED, null)
                ),
                90, 50
        );

        ImmutableTagPlan plan = ImmutableTagPlan.fromSession(session, config("raw-key", "final-key"));
        PlanApplyOperation operation = PlanApplyOperation.create(plan);
        workflow.applyTags(plan, operation, ignored -> { });

        assertEquals(PlanApplyOperation.State.COMPLETE, operation.state(), "reconciled operation completes");
        assertTrue(rawApi.untaggedAssetIds.contains("raw-keeper"), "removes stale unused tag from keeper RAW");
        assertTrue(rawApi.untaggedAssetIds.contains("raw-unused"), "removes stale keeper tag from unused RAW");
        assertTrue(finalApi.untaggedAssetIds.contains("final-matched"), "removes stale No RAW tag from matched final");
        assertTrue(finalApi.untaggedAssetIds.contains("final-unmatched"), "removes stale RAW Found tag from unmatched final");
    }

    private static void omitsApiKeysFromStatusJson() throws Exception {
        ImmichConfig config = new ImmichConfig(
                "http://immich.local/api",
                "raw-secret",
                "final-secret",
                "raw-user",
                "final-user",
                "Keeper",
                "not used",
                "RAW Found",
                "No RAW",
                "duplicate",
                1000,
                10000
        );
        PhotoCullServer server = new PhotoCullServer(8356, Path.of("build", "test-config"), config, "app-secret");
        Method statusJson = PhotoCullServer.class.getDeclaredMethod("statusJson");
        statusJson.setAccessible(true);
        String json = (String) statusJson.invoke(server);
        Map<String, Object> parsed = Json.parseObject(json);
        Map<?, ?> immich = (Map<?, ?>) parsed.get("immich");

        assertEquals(Boolean.TRUE, immich.get("rawApiKeyConfigured"), "raw key status");
        assertEquals(Boolean.TRUE, immich.get("finalApiKeyConfigured"), "final key status");
        assertEquals("RAW_IMMICH_API_KEY", immich.get("rawApiKeySource"), "raw status source");
        assertEquals("FINAL_IMMICH_API_KEY", immich.get("finalApiKeySource"), "final status source");
        assertTrue(!json.contains("raw-secret"), "raw key hidden");
        assertTrue(!json.contains("final-secret"), "final key hidden");
    }

    private static void reportsStateRestoreProgress() throws Exception {
        Path configDir = Path.of("build", "test-config", "restore-status-" + UUID.randomUUID());
        PhotoCullServer server = new PhotoCullServer(8356, configDir, config("raw-key", "final-key"));
        Method statusJson = PhotoCullServer.class.getDeclaredMethod("statusJson");
        statusJson.setAccessible(true);
        assertEquals(Boolean.TRUE, Json.parseObject((String) statusJson.invoke(server)).get("stateRestoring"),
                "status reports that saved state is still loading");

        Method restorePersistedState = PhotoCullServer.class.getDeclaredMethod("restorePersistedState");
        restorePersistedState.setAccessible(true);
        restorePersistedState.invoke(server);
        assertEquals(Boolean.FALSE, Json.parseObject((String) statusJson.invoke(server)).get("stateRestoring"),
                "status reports when saved state is ready");
    }

    private static ImmichConfig config(String rawKey, String finalKey) {
        return new ImmichConfig(
                "http://immich.local/api",
                rawKey,
                finalKey,
                "raw-user",
                "final-user",
                "Keeper",
                "not used",
                "RAW Found",
                "No RAW",
                "duplicate",
                1000,
                10000
        );
    }

    private static ImmichConfig configForUrl(String url) {
        return new ImmichConfig(
                url,
                "raw-key",
                "final-key",
                "raw-user",
                "final-user",
                "Keeper",
                "not used",
                "RAW Found",
                "No RAW",
                "duplicate",
                1000,
                10000
        );
    }

    private static ImmichAsset asset(String id, String ownerId, String fileName) {
        return new ImmichAsset(
                id,
                ownerId,
                fileName,
                "/upload/" + fileName,
                "",
                "IMAGE",
                "",
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                false,
                "",
                "",
                1
        );
    }

    private static PhotoFile photo(String id, String ownerId, String fileName) {
        return photo(id, ownerId, fileName, 1);
    }

    private static PhotoFile photo(String id, String ownerId, String fileName, long sizeBytes) {
        return photo(id, ownerId, fileName, sizeBytes, null);
    }

    private static PhotoFile photo(String id, String ownerId, String fileName, long sizeBytes, String checksum) {
        return PhotoFile.fromImmichAsset(
                id,
                ownerId,
                fileName,
                "/upload/" + fileName,
                sizeBytes,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
                "",
                "",
                checksum
        );
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label + ": expected true");
        }
    }

    private static final class RecordingImmichApi implements ImmichApi {
        private final List<ImmichAsset> assets;
        private final List<String> searchOwners = new ArrayList<>();
        private final List<String> ensuredTags = new ArrayList<>();
        private final List<String> taggedAssetIds = new ArrayList<>();
        private final List<String> untaggedAssetIds = new ArrayList<>();
        private final List<String> albumAssetIds = new ArrayList<>();
        private final List<ImmichTag> visibleTags = new ArrayList<>();
        private final List<ImmichAlbum> visibleAlbums = new ArrayList<>();

        private RecordingImmichApi() {
            this(List.of());
        }

        private RecordingImmichApi(List<ImmichAsset> assets) {
            this.assets = assets;
        }

        @Override
        public List<ImmichUser> users() {
            return List.of();
        }

        @Override
        public List<ImmichAsset> imageAssetsForOwner(String ownerId, Set<String> extensions, Consumer<String> progress) {
            searchOwners.add(ownerId);
            return assets.stream()
                    .filter(asset -> ownerId.equals(asset.ownerId()))
                    .filter(asset -> asset.hasExtensionIn(extensions))
                    .toList();
        }

        @Override
        public ImmichTag ensureTag(String name) {
            ensuredTags.add(name);
            ImmichTag existing = visibleTags.stream().filter(tag -> tag.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (existing != null) {
                return existing;
            }
            ImmichTag created = new ImmichTag("tag-" + name, name, name);
            visibleTags.add(created);
            return created;
        }

        @Override
        public List<ImmichTag> tags() {
            return List.copyOf(visibleTags);
        }

        @Override
        public int tagAssets(String tagId, List<String> assetIds) {
            taggedAssetIds.addAll(assetIds);
            return assetIds.size();
        }

        @Override
        public int untagAssets(String tagId, List<String> assetIds) {
            untaggedAssetIds.addAll(assetIds);
            return assetIds.size();
        }

        @Override
        public List<ImmichAlbum> albums() {
            return List.copyOf(visibleAlbums);
        }

        @Override
        public ImmichAlbum ensureAlbum(String name) {
            ImmichAlbum existing = visibleAlbums.stream().filter(album -> album.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (existing != null) {
                return existing;
            }
            ImmichAlbum created = new ImmichAlbum("album-" + name, name);
            visibleAlbums.add(created);
            return created;
        }

        @Override
        public int addAssetsToAlbum(String albumId, List<String> assetIds) {
            albumAssetIds.addAll(assetIds);
            return assetIds.size();
        }

        @Override
        public int removeAssetsFromAlbum(String albumId, List<String> assetIds) {
            albumAssetIds.removeAll(assetIds);
            return assetIds.size();
        }

        @Override
        public void deleteAlbum(String albumId) {
            visibleAlbums.removeIf(album -> album.id().equals(albumId));
        }

        @Override
        public void deleteTag(String tagId) {
            visibleTags.removeIf(tag -> tag.id().equals(tagId));
        }

        @Override
        public byte[] thumbnail(String assetId) {
            return new byte[0];
        }
    }
}
