package com.shilian.packager.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 内存 fake：源文件预先 put 进来，产物写到内存，uri 形如 {@code memory://out/<name>}。 */
public class InMemoryStorage implements Storage {

    public static final String OUT_PREFIX = "memory://out/";

    private final Map<String, byte[]> sources = new ConcurrentHashMap<>();
    private final Map<String, byte[]> outputs = new ConcurrentHashMap<>();

    public InMemoryStorage put(String uri, byte[] data) {
        sources.put(uri, data.clone());
        return this;
    }

    @Override
    public byte[] read(String uri) {
        byte[] data = sources.get(uri);
        if (data == null) {
            throw new StorageError("读取失败：" + uri);
        }
        return data.clone();
    }

    @Override
    public String write(String name, byte[] data) {
        String uri = OUT_PREFIX + name;
        outputs.put(uri, data.clone());
        return uri;
    }

    public byte[] output(String uri) {
        byte[] data = outputs.get(uri);
        if (data == null) {
            throw new StorageError("产物不存在：" + uri);
        }
        return data.clone();
    }

    @Override
    public String signedUrl(String uri, int expiresIn) {
        if (!outputs.containsKey(uri) && !sources.containsKey(uri)) {
            throw new StorageError("对象不存在：" + uri);
        }
        return uri + "?expires_in=" + expiresIn;
    }
}
