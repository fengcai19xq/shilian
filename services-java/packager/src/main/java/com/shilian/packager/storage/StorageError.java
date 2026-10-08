package com.shilian.packager.storage;

/** 存储层错误（读不到源文件、写出失败、越界 uri 等）。 */
public class StorageError extends RuntimeException {

    public StorageError(String message) {
        super(message);
    }

    public StorageError(String message, Throwable cause) {
        super(message, cause);
    }
}
