package com.shilian.matcher.model;

import java.util.ArrayList;
import java.util.List;

public record Checklist(String checklistId, String title, List<ChecklistItem> items) {

    public Checklist {
        items = items == null ? new ArrayList<>() : items;
    }

    public Checklist(String checklistId, String title) {
        this(checklistId, title, new ArrayList<>());
    }
}
