package com.shilian.matcher.service;

/** 当前状态不允许该操作。 */
public class StateException extends RuntimeException {
    public StateException(String message) {
        super(message);
    }
}
