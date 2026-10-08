package com.shilian.wecomsync.identity;

/** 全量同步拿到 0 个成员而本地仍有在职用户：大概率是企微侧异常，拒绝执行以免误判全员离职。 */
public class EmptySnapshotException extends RuntimeException {

    public EmptySnapshotException(String message) {
        super(message);
    }
}
