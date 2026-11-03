package com.shilian.wecomsync.weknora;

/** WeKnora 身份接口调用失败。message 只含状态码与端点，不回显服务端原文与用户数据。 */
public class WeknoraApiException extends RuntimeException {

    public WeknoraApiException(String message) {
        super(message);
    }

    public WeknoraApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
