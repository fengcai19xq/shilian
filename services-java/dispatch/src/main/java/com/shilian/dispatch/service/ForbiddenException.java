package com.shilian.dispatch.service;

/** 操作人无权执行（403）。 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
