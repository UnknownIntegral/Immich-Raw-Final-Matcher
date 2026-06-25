package com.photocull.matcher;

/** One visible contribution to the match confidence score. */
public record MatchScoreDetail(String key, String label, int weight, int points, String note) {
    public MatchScoreDetail {
        key = key == null ? "" : key;
        label = label == null ? "" : label;
        note = note == null ? "" : note;
    }
}
