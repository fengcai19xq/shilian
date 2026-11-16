package com.shilian.matcher.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Constraints;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.ItemStatus;
import com.shilian.matcher.model.MatchTask;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.model.ScoredCandidate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {

    private static Checklist checklist() {
        ChecklistItem item = new ChecklistItem("it_1", "cl_1", "3.1", "审计报告", "AUDIT_REPORT",
                "resolved", "审计报告", new Constraints(List.of("2023"), Scope.CONSOLIDATED, 1, true));
        return new Checklist("cl_1", "测试清单", List.of(item));
    }

    @Test
    void 重启后恢复清单任务结果与审计(@TempDir Path dir) {
        Path file = dir.resolve("store.json");
        MemoryStore first = new MemoryStore(file);
        first.saveChecklist(checklist());
        MatchTask task = first.createTask("cl_1", 1);
        task.setState(MatchTask.SUCCEEDED);
        task.incrementDone();
        ScoredCandidate scored = new ScoredCandidate("d_1", "审计报告.pdf", "oss://docs/d_1.pdf",
                false, List.of(), 0.86, 0.91, 0.1, 0.91, "ok");
        task.getResults().add(new ItemResult("it_1", "3.1", "AUDIT_REPORT", "审计报告",
                ItemStatus.MATCHED, 0.91, List.of(scored), List.of("d_1"), "u_1", "已确认"));
        first.putResult("cl_1", task.getResults().get(0));
        first.addAudit("cl_1", "item_confirmed", "it_1", "u_1", Map.of("to", "matched"));
        first.persist();

        MemoryStore second = new MemoryStore(file);
        assertThat(second.getChecklist("cl_1").items()).hasSize(1);
        assertThat(second.getChecklist("cl_1").items().get(0).constraints().scope())
                .isEqualTo(Scope.CONSOLIDATED);
        MatchTask restored = second.getTask(task.getTaskId());
        assertThat(restored.getState()).isEqualTo(MatchTask.SUCCEEDED);
        assertThat(restored.getDone()).isEqualTo(1);
        assertThat(restored.getResults()).hasSize(1);
        assertThat(restored.getResults().get(0).candidates().get(0).pSatisfies()).isEqualTo(0.91);
        assertThat(second.getResult("cl_1", "it_1").status()).isEqualTo(ItemStatus.MATCHED);
        assertThat(second.listAudit("cl_1")).hasSize(1);
    }

    @Test
    void 重启时运行中任务标记失败(@TempDir Path dir) {
        Path file = dir.resolve("store.json");
        MemoryStore first = new MemoryStore(file);
        first.saveChecklist(checklist());
        MatchTask task = first.createTask("cl_1", 1);
        task.setState(MatchTask.RUNNING);
        first.persist();

        MemoryStore second = new MemoryStore(file);
        MatchTask restored = second.getTask(task.getTaskId());
        assertThat(restored.getState()).isEqualTo(MatchTask.FAILED);
        assertThat(restored.getError()).contains("服务重启");
    }

    @Test
    void 快照损坏时启动失败(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("store.json");
        Files.writeString(file, "not-json");
        assertThatThrownBy(() -> new MemoryStore(file)).isInstanceOf(IllegalStateException.class);
    }
}
