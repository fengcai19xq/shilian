package com.shilian.packager.storage;

/**
 * 对象存储桩。生产环境在这里接 OSS/S3 SDK：凭据一律读环境变量，不在代码里硬编码。
 * 留桩是为了保证执行器只依赖 {@link Storage} 接口，不依赖具体实现。
 */
public class ObjectStorage implements Storage {

    private final String bucket;
    private final String prefix;

    public ObjectStorage(String bucket) {
        this(bucket, "");
    }

    public ObjectStorage(String bucket, String prefix) {
        this.bucket = bucket;
        this.prefix = prefix;
    }

    public String bucket() {
        return bucket;
    }

    public String prefix() {
        return prefix;
    }

    @Override
    public byte[] read(String uri) {
        throw new UnsupportedOperationException("对象存储读取待接入 SDK");
    }

    @Override
    public String write(String name, byte[] data) {
        throw new UnsupportedOperationException("对象存储写出待接入 SDK");
    }

    @Override
    public String signedUrl(String uri, int expiresIn) {
        throw new UnsupportedOperationException("签名 URL 待接入 SDK");
    }
}
