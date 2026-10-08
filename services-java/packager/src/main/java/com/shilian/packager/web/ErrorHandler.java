package com.shilian.packager.web;

import com.shilian.packager.PackagingError;
import com.shilian.packager.storage.StorageError;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 错误统一为 {"error": 类型, "message": 说明}。 */
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(PackagingError.class)
    public ResponseEntity<Map<String, String>> packaging(PackagingError e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "packaging_error", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_manifest", "manifest 不合法：无法解析");
    }

    @ExceptionHandler(StorageError.class)
    public ResponseEntity<Map<String, String>> storage(StorageError e) {
        return body(HttpStatus.BAD_GATEWAY, "storage_error", e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String type, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", type, "message", msg));
    }
}
