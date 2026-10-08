package com.shilian.packager;

/** manifest 或源文件不满足成册条件（manifest 不合法、源文件不是 PDF、页码越界等）。 */
public class PackagingError extends IllegalArgumentException {

    public PackagingError(String message) {
        super(message);
    }

    public PackagingError(String message, Throwable cause) {
        super(message, cause);
    }
}
