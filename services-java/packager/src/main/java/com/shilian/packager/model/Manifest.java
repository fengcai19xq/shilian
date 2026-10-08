package com.shilian.packager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/** 成册执行器的唯一输入，字段与 contracts/packaging.md 一致。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Manifest(
        String packageId,
        String title,
        String watermark,
        List<OutputKind> outputs,
        List<Entry> entries) {

    public Manifest {
        watermark = watermark == null ? "" : watermark;
        outputs = outputs == null ? List.of(OutputKind.values()) : List.copyOf(outputs);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
