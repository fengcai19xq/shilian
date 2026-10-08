package com.shilian.wecomsync.identity;

/** 成员已离职或被禁用，身份失效。 */
public class PrincipalInactiveException extends RuntimeException {

    public PrincipalInactiveException(String message) {
        super(message);
    }
}
