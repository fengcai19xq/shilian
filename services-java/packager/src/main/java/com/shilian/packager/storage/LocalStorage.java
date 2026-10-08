package com.shilian.packager.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地目录实现：源文件与产物都落在挂进沙箱的临时目录里。
 *
 * <p>uri 支持相对路径（{@code docs/d_2031.pdf}）与对象存储风格（{@code oss://docs/d_2031.pdf}，scheme
 * 被剥掉后当相对路径用）；解析结果必须落在 root 内，防目录穿越。
 */
public class LocalStorage implements Storage {

    private final Path root;
    private final Path outDir;

    public LocalStorage(Path root) {
        this(root, root.resolve("out"));
    }

    public LocalStorage(Path root, Path outDir) {
        this.root = root.toAbsolutePath().normalize();
        this.outDir = outDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            Files.createDirectories(this.outDir);
        } catch (IOException e) {
            throw new StorageError("初始化目录失败：" + root, e);
        }
    }

    public Path root() {
        return root;
    }

    public Path outDir() {
        return outDir;
    }

    static String stripScheme(String uri) {
        int idx = uri.indexOf("://");
        String rel = idx >= 0 ? uri.substring(idx + 3) : uri;
        int i = 0;
        while (i < rel.length() && rel.charAt(i) == '/') {
            i++;
        }
        return rel.substring(i);
    }

    public Path pathOf(String uri) {
        Path path = root.resolve(stripScheme(uri)).normalize();
        if (!path.startsWith(root)) {
            throw new StorageError("越界的 uri：" + uri);
        }
        return path;
    }

    @Override
    public byte[] read(String uri) {
        Path path = pathOf(uri);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new StorageError("读取失败：" + uri, e);
        }
    }

    @Override
    public String write(String name, byte[] data) {
        Path path = outDir.resolve(name).normalize();
        if (!path.startsWith(outDir)) {
            throw new StorageError("越界的产物名：" + name);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, data);
        } catch (IOException e) {
            throw new StorageError("写出失败：" + name, e);
        }
        return path.toString();
    }

    @Override
    public String signedUrl(String uri, int expiresIn) {
        Path abs = Path.of(uri).normalize();
        Path path = abs.isAbsolute() && (abs.startsWith(outDir) || abs.startsWith(root))
                ? abs
                : pathOf(uri);
        return "file://" + path + "?expires_in=" + expiresIn;
    }
}
