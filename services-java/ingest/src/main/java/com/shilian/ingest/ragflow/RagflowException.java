package com.shilian.ingest.ragflow;

public class RagflowException extends RuntimeException {

    public RagflowException(String message) {
        super(message);
    }

    public RagflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
