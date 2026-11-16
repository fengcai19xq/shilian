package com.shilian.matcher.web;

import com.shilian.matcher.model.MatchTask;
import com.shilian.matcher.port.RetrievalPrincipal;
import java.util.List;

/** 接口层请求 / 响应体。 */
public final class Dtos {

    private Dtos() {
    }

    public record RunRequest(RetrievalPrincipal principal) {
    }

    public record RunResponse(String taskId, String checklistId, String state) {
    }

    public record TaskResponse(MatchTask task, double progress) {
    }

    public record ConfirmRequest(List<String> docIds, String note) {
        public ConfirmRequest {
            docIds = docIds == null ? List.of() : docIds;
            note = note == null ? "" : note;
        }
    }
}
