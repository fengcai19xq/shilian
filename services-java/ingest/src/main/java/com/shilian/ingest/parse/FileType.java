package com.shilian.ingest.parse;

import java.util.Locale;
import java.util.Optional;

/** 支持解析的文件类型（按扩展名识别，再用文件头校验）。 */
public enum FileType {
    DOCX, PDF, XLSX, PPTX;

    public String extension() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 取文件名扩展名（小写，不含点）；无扩展名返回空串。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Optional<FileType> fromFileName(String fileName) {
        String ext = extensionOf(fileName);
        for (FileType t : values()) {
            if (t.extension().equals(ext)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
