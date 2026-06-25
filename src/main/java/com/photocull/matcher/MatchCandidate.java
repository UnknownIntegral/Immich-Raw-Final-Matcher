package com.photocull.matcher;

import java.util.List;

/** A scored RAW alternative retained for an interactive review decision. */
public record MatchCandidate(PhotoFile raw, int score, String reason, List<MatchScoreDetail> scoreDetails) {
    public MatchCandidate(PhotoFile raw, int score, String reason) {
        this(raw, score, reason, List.of());
    }

    public MatchCandidate {
        scoreDetails = scoreDetails == null ? List.of() : List.copyOf(scoreDetails);
    }
}
