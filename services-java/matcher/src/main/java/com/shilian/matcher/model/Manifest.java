package com.shilian.matcher.model;

import java.util.List;

/** 导出给 packager 的 manifest（contracts/packaging.md）。 */
public record Manifest(String packageId, String title, String watermark, List<String> outputs,
        List<ManifestEntry> entries) {

    public static final List<String> DEFAULT_OUTPUTS = List.of("zip", "merged_pdf", "checklist_xlsx");

    public Manifest {
        outputs = outputs == null ? DEFAULT_OUTPUTS : List.copyOf(outputs);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
