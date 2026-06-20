package com.photocull.matcher;

import java.nio.file.Path;

public record MatchResult(
        PhotoFile finished,
        PhotoFile raw,
        int score,
        String reason,
        MatchStatus status,
        Path movedTo
) {
    public Path rawPathOrNull() {
        return raw == null ? null : raw.path();
    }

    public boolean isAcceptedForMove() {
        return raw != null && (status == MatchStatus.AUTO_ACCEPTED || status == MatchStatus.ACCEPTED);
    }

    public MatchResult withStatus(MatchStatus newStatus) {
        return new MatchResult(finished, raw, score, reason, newStatus, movedTo);
    }

    public MatchResult withMoveStatus(MatchStatus newStatus, Path target) {
        return new MatchResult(finished, raw, score, reason, newStatus, target);
    }
}

