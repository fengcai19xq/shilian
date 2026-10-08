package com.shilian.matcher.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 异步匹配任务，state 取 queued / running / succeeded / failed。后台线程写、接口线程读。 */
public class MatchTask {
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    private final String taskId;
    private final String checklistId;
    private final int total;
    private volatile String state = QUEUED;
    private volatile int done;
    private volatile String error;
    private final List<ItemResult> results = new CopyOnWriteArrayList<>();

    public MatchTask(String taskId, String checklistId, int total) {
        this.taskId = taskId;
        this.checklistId = checklistId;
        this.total = total;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getChecklistId() {
        return checklistId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public int getTotal() {
        return total;
    }

    public int getDone() {
        return done;
    }

    public void incrementDone() {
        done++;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<ItemResult> getResults() {
        return results;
    }

    /** 进度，保留 4 位小数；不作为 task 的 JSON 字段，由接口层单独返回。 */
    public double progress() {
        if (total == 0) {
            return 1.0;
        }
        return BigDecimal.valueOf((double) done / total).setScale(4, RoundingMode.HALF_EVEN).doubleValue();
    }
}
