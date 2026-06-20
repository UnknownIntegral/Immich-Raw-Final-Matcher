package com.photocull.immich;

import java.util.Map;

public record ImmichUser(String id, String name, String email) {
    public static ImmichUser fromJson(Map<String, Object> values) {
        return new ImmichUser(
                string(values.get("id")),
                string(values.get("name")),
                string(values.get("email"))
        );
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
