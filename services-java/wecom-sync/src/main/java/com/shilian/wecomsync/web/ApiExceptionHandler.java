package com.shilian.wecomsync.web;

import com.shilian.wecomsync.identity.EmptySnapshotException;
import com.shilian.wecomsync.identity.NotFoundException;
import com.shilian.wecomsync.identity.PrincipalInactiveException;
import com.shilian.wecomsync.web.Dtos.ErrorResponse;
import com.shilian.wecomsync.wecom.WecomApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(PrincipalInactiveException.class)
    public ResponseEntity<ErrorResponse> inactive(PrincipalInactiveException e) {
        return error(HttpStatus.GONE, "principal_inactive", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? "malformed request body" : e.getMessage();
        return error(HttpStatus.BAD_REQUEST, "bad_request", msg);
    }

    @ExceptionHandler(EmptySnapshotException.class)
    public ResponseEntity<ErrorResponse> emptySnapshot(EmptySnapshotException e) {
        return error(HttpStatus.CONFLICT, "empty_snapshot", e.getMessage());
    }

    @ExceptionHandler(WecomApiException.class)
    public ResponseEntity<ErrorResponse> wecom(WecomApiException e) {
        log.warn("wecom call failed: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "wecom_unavailable", "wecom api call failed, nothing was written");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
