package com.shilian.dispatch.port;

/** 发送结果。ok=false 时 error 给出原因，不抛异常。 */
public record NotifyResult(boolean ok, String msgId, String error) {

    public static NotifyResult success(String msgId) {
        return new NotifyResult(true, msgId, null);
    }

    public static NotifyResult failure(String error) {
        return new NotifyResult(false, null, error);
    }
}
