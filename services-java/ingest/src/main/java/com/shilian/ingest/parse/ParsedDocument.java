package com.shilian.ingest.parse;

import java.util.List;

public record ParsedDocument(FileType type, List<PageText> pages) {

    public int pageCount() {
        return pages.stream().mapToInt(PageText::page).max().orElse(0);
    }

    public boolean hasText() {
        return pages.stream().anyMatch(p -> !p.text().isBlank());
    }
}
