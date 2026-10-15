package com.shilian.dispatch.service;

/** 状态机不允许的迁移（409）。 */
public class StateException extends RuntimeException {

    public StateException(String message) {
        super(message);
    }
}
