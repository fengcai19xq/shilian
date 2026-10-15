package com.shilian.dispatch.service;

/** 请求校验失败（422）。 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
