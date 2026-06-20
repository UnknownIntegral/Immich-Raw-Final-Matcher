package com.photocull.immich;

import java.util.Map;

public record ImmichTag(String id, String name, String value) {
    public static ImmichTag fromJson(Map<String, Object> values) {
        return new ImmichTag(
                string(values.get("id")),
                string(values.get("name")),
                string(values.get("value"))
        );
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
