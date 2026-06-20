package com.photocull;

import com.photocull.matcher.MatchEngine;
import com.photocull.matcher.MatchResult;
import com.photocull.matcher.MatchStatus;
import com.photocull.matcher.PhotoFile;
import com.photocull.server.FinalTagPlanItem;
import com.photocull.server.Json;
import com.photocull.server.ScanSession;
import com.photocull.server.TagPlanItem;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Tests {
    private Tests() {
    }

    public static void main(String[] args) {
        parsesJsonObjects();
        buildsImmichPhotoFiles();
        createsAssetIdTagPlans();
        createsFinalAccountTagPlans();
        tagsOnlyLowerFileSizeDuplicateFinals();
        flagsFinalsWithMultipleStrongRawCandidates();
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
        assertEquals("cr3", raw.extension(), "extension");
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
                ""
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
                ""
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
                "COPY_0001.CR3",
                "/upload/raw/COPY_0001.CR3",
                1,
                Instant.parse("2024-01-01T10:00:00Z"),
                Instant.parse("2024-01-01T10:00:00Z"),
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

        List<MatchResult> matches = new MatchEngine().match(List.of(firstRaw, secondRaw), List.of(finished), 90, ignored -> {
        });
        MatchResult result = matches.get(0);
        assertEquals(MatchStatus.NEEDS_REVIEW, result.status(), "ambiguous raw match status");
        assertTrue(result.reason().contains("multiple strong RAW candidates"), "ambiguous raw match reason");
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
}
