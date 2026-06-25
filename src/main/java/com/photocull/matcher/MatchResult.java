package com.photocull.matcher;

import java.nio.file.Path;
import java.util.List;

public record MatchResult(
        PhotoFile finished,
        PhotoFile raw,
        int score,
        String reason,
        MatchStatus status,
        Path movedTo,
        List<MatchCandidate> candidates,
        List<MatchScoreDetail> scoreDetails
) {
    public MatchResult(
            PhotoFile finished,
            PhotoFile raw,
            int score,
            String reason,
            MatchStatus status,
            Path movedTo
    ) {
        this(finished, raw, score, reason, status, movedTo, raw == null ? List.of() : List.of(new MatchCandidate(raw, score, reason)), List.of());
    }

    public MatchResult(
            PhotoFile finished,
            PhotoFile raw,
            int score,
            String reason,
            MatchStatus status,
            Path movedTo,
            List<MatchCandidate> candidates
    ) {
        this(finished, raw, score, reason, status, movedTo, candidates, List.of());
    }

    public MatchResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        scoreDetails = scoreDetails == null ? List.of() : List.copyOf(scoreDetails);
    }

    public Path rawPathOrNull() {
        return raw == null ? null : raw.path();
    }

    public boolean isAcceptedForMove() {
        return raw != null && (status == MatchStatus.AUTO_ACCEPTED || status == MatchStatus.ACCEPTED);
    }

    public MatchResult withStatus(MatchStatus newStatus) {
        return new MatchResult(finished, raw, score, reason, newStatus, movedTo, candidates, scoreDetails);
    }

    public MatchResult withMoveStatus(MatchStatus newStatus, Path target) {
        return new MatchResult(finished, raw, score, reason, newStatus, target, candidates, scoreDetails);
    }

    public MatchResult withSelectedRaw(PhotoFile selectedRaw) {
        if (selectedRaw == null) {
            throw new IllegalArgumentException("A selected RAW candidate is required.");
        }
        MatchCandidate candidate = candidates.stream()
                .filter(item -> sameAsset(item.raw(), selectedRaw))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("The selected RAW is not a candidate for this final image."));
        return new MatchResult(finished, selectedRaw, candidate.score(), candidate.reason(), status, movedTo, candidates, candidate.scoreDetails());
    }

    private boolean sameAsset(PhotoFile first, PhotoFile second) {
        if (first.immichAssetId() != null && second.immichAssetId() != null) {
            return first.immichAssetId().equals(second.immichAssetId());
        }
        return first.path().equals(second.path());
    }
}
