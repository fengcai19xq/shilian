package com.shilian.ingest.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地卷实现：根目录由 INGEST_FILE_STORE_PATH 指定，与 packager 的
 * PACKAGER_STORAGE_ROOT 挂同一个卷即可按 {@code local://docs/...} 读取。
 *
 * <p>文件按 doc_id 建目录，文件名做路径分隔符与越界清理；写入用临时文件 + 原子替换。
 */
public class LocalDocumentStorage implements DocumentStorage {

    private final Path root;

    public LocalDocumentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("初始化原始文件存储失败：" + root, e);
        }
    }

    @Override
    public String save(String docId, String fileName, byte[] content) {
        String safe = safeName(fileName);
        Path dir = root.resolve("docs").resolve(safeSegment(docId)).normalize();
        Path target = dir.resolve(safe).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的文件路径");
        }
        try {
            Files.createDirectories(dir);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return "local://docs/" + safeSegment(docId) + "/" + safe;
        } catch (IOException e) {
            throw new IllegalStateException("保存原始文件失败：doc=" + docId, e);
        }
    }

    @Override
    public byte[] read(String uri) {
        String relative = uri == null ? "" : uri;
        int idx = relative.indexOf("://");
        if (idx >= 0) {
            relative = relative.substring(idx + 3);
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("越界的文件 URI");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取原始文件失败：" + uri, e);
        }
    }

    static String safeSegment(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value.strip();
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String base;
        try {
            base = Path.of(fileName).getFileName().toString();
        } catch (RuntimeException e) {
            base = fileName;
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return base.isBlank() ? "unnamed" : base;
    }
}
