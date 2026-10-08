package com.shilian.packager.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Comparator;
import java.util.List;

/** 清单项。period / owner_dept 为契约「只增不改」补充的可选字段。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Entry(
        String no,
        String stdName,
        List<FileRef> files,
        EntryStatus status,
        String note,
        String period,
        String ownerDept) {

    public Entry {
        files = files == null ? List.of() : List.copyOf(files);
        status = status == null ? EntryStatus.MATCHED : status;
        note = note == null ? "" : note;
    }

    public static Entry of(String no, String stdName, String period, List<FileRef> files) {
        return new Entry(no, stdName, files, null, null, period, null);
    }

    /** 按 order 稳定排序；order 相同的保持 manifest 里的先后顺序。 */
    @JsonIgnore
    public List<FileRef> orderedFiles() {
        return files.stream().sorted(Comparator.comparingInt(FileRef::order)).toList();
    }

    @JsonIgnore
    public boolean isMissing() {
        return status == EntryStatus.MISSING;
    }

    /** 是否入册：missing 不入册；没有文件的条目同样不入册。 */
    @JsonIgnore
    public boolean isPackable() {
        return !isMissing() && !files.isEmpty();
    }
}
