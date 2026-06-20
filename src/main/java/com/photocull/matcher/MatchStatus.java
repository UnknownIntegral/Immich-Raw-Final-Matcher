package com.photocull.matcher;

public enum MatchStatus {
    AUTO_ACCEPTED("Auto accepted"),
    AUTO_REJECTED("Auto rejected"),
    NEEDS_REVIEW("Needs review"),
    ACCEPTED("Accepted"),
    REJECTED("Rejected"),
    MOVED("Moved"),
    MOVE_FAILED("Move failed");

    private final String label;

    MatchStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
