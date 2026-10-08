package com.shilian.wecomsync.wecom;

/** 调用企微失败（网络、限流、errcode≠0 等）。 */
public class WecomApiException extends RuntimeException {

    public WecomApiException(String message) {
        super(message);
    }

    public WecomApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
