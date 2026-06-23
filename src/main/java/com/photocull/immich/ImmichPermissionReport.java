package com.photocull.immich;

import java.time.Instant;
import java.util.List;

/** Sanitized, display-ready permission results. API key values are never stored here. */
public record ImmichPermissionReport(Instant checkedAt, Account raw, Account finalAccount) {
    public enum State { PASS, FAIL, SKIPPED, UNSUPPORTED }

    public record Account(String label, List<Check> checks) {
        public Account {
            checks = List.copyOf(checks);
        }
    }

    public record Check(String capability, State state, String detail) {
    }
}
