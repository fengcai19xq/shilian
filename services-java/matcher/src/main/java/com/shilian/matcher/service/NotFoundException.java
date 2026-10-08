package com.shilian.matcher.service;

/** 资源不存在。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String id) {
        super(id);
    }
}
