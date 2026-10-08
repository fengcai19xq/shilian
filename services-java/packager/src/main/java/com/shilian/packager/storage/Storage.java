package com.shilian.packager.storage;

import com.shilian.packager.core.SourceReader;

/** 执行器依赖的最小存储接口：文件读取与产物写出都走这层，执行器本身不碰文件系统与网络。 */
public interface Storage extends SourceReader {

    /** 读取源文件字节，读不到抛 {@link StorageError}。 */
    @Override
    byte[] read(String uri);

    /** 写出产物，返回产物 uri；同名重复写出覆盖。 */
    String write(String name, byte[] data);

    /** 返回带时效的访问 URL。 */
    String signedUrl(String uri, int expiresIn);

    default String signedUrl(String uri) {
        return signedUrl(uri, 3600);
    }
}
