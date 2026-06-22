package com.photocull.matcher;

/** A scored RAW alternative retained for an interactive review decision. */
public record MatchCandidate(PhotoFile raw, int score, String reason) {
}
