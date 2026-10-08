package com.shilian.wecomsync.identity;

public enum SyncMode {
    FULL, INCREMENTAL;

    public static SyncMode parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("full")) {
            return FULL;
        }
        if (raw.equalsIgnoreCase("incremental")) {
            return INCREMENTAL;
        }
        throw new IllegalArgumentException("mode must be full or incremental");
    }

    public String value() {
        return name().toLowerCase();
    }
}
