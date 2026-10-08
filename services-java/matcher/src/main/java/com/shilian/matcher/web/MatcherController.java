package com.shilian.matcher.web;

import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.Manifest;
import com.shilian.matcher.model.MatchTask;
import com.shilian.matcher.service.MatcherService;
import com.shilian.matcher.service.NotFoundException;
import com.shilian.matcher.service.StateException;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 接口层（contracts/matching.md「接口」一节）。
 *
 * <p>操作人取自网关注入的 X-User-Id 请求头，不由前端自报。错误体为 {"detail": "..."}，与 Python 版一致。
 */
@RestController
@RequestMapping("/matcher")
public class MatcherController {

    private final MatcherService service;
    private final Executor executor;

    public MatcherController(MatcherService service, @Qualifier("matcherExecutor") Executor executor) {
        this.service = service;
        this.executor = executor;
    }

    @PostMapping("/checklists")
    @ResponseStatus(HttpStatus.CREATED)
    public Checklist uploadChecklist(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "title", defaultValue = "") String title,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String operator = userId == null || userId.isEmpty() ? "system" : userId;
        if (file == null && (text == null || text.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要上传 file 或提供 text");
        }
        try {
            if (file != null) {
                String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
                return service.upload(file.getBytes(), filename, title, operator);
            }
            return service.uploadText(text, title, operator);
        } catch (Exception e) {
            // 解析失败统一回 422
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "清单解析失败：" + e.getClass().getSimpleName());
        }
    }

    @PostMapping("/checklists/{checklistId}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Dtos.RunResponse runMatch(@PathVariable String checklistId) {
        MatchTask task;
        try {
            task = service.createTask(checklistId);
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "清单不存在");
        }
        String state = task.getState();
        executor.execute(() -> service.runTask(task.getTaskId()));
        return new Dtos.RunResponse(task.getTaskId(), checklistId, state);
    }

    @GetMapping("/tasks/{taskId}")
    public Dtos.TaskResponse getTask(@PathVariable String taskId) {
        try {
            MatchTask task = service.getTask(taskId);
            return new Dtos.TaskResponse(task, task.progress());
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        }
    }

    @PostMapping("/items/{itemId}/confirm")
    public ItemResult confirmItem(
            @PathVariable String itemId,
            @RequestBody(required = false) Dtos.ConfirmRequest body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少操作人");
        }
        Dtos.ConfirmRequest req = body != null ? body : new Dtos.ConfirmRequest(null, null);
        try {
            return service.confirm(itemId, req.docIds(), userId, req.note());
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "清单项不存在");
        } catch (StateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/checklists/{checklistId}/manifest")
    public Manifest exportManifest(
            @PathVariable String checklistId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "watermark", defaultValue = "") String watermark) {
        try {
            return service.manifest(checklistId, title, watermark);
        } catch (NotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "清单不存在");
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("detail", e.getReason() == null ? "" : e.getReason()));
    }
}
