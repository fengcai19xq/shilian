package com.shilian.packager.core;

/** 读源文件的函数：执行器只依赖它，不直接碰存储实现。 */
@FunctionalInterface
public interface SourceReader {
    byte[] read(String uri);
}
