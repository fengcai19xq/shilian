package com.shilian.matcher.model;

import java.util.List;

public record ManifestEntry(String no, String stdName, List<ManifestFile> files, ItemStatus status, String note) {

    public ManifestEntry {
        files = files == null ? List.of() : List.copyOf(files);
        note = note == null ? "" : note;
    }
}
