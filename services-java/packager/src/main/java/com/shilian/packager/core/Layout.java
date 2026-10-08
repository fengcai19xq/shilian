package com.shilian.packager.core;

import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import com.shilian.packager.model.Manifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 合订本排版计划：算出每个条目在合订 PDF 里的起止页码。
 *
 * <p>目录页码、核对表页码列都以这里为准，保证两边一致。页码以合订本物理页计，封面为第 1 页。
 */
public final class Layout {

    public static final int COVER_PAGES = 1;
    public static final int TOC_ROWS_PER_PAGE = 28;

    /** 单个条目在合订本里的页码区间（闭区间）。 */
    public record EntrySpan(Entry entry, int start, int end) {
        public String label() {
            return start == end ? String.valueOf(start) : start + "-" + end;
        }
    }

    private final int tocPages;
    private final List<EntrySpan> spans;

    private Layout(int tocPages, List<EntrySpan> spans) {
        this.tocPages = tocPages;
        this.spans = List.copyOf(spans);
    }

    public int tocPages() {
        return tocPages;
    }

    public List<EntrySpan> spans() {
        return spans;
    }

    public int bodyStart() {
        return COVER_PAGES + tocPages + 1;
    }

    public int totalPages() {
        return spans.isEmpty() ? COVER_PAGES + tocPages : spans.get(spans.size() - 1).end();
    }

    public Optional<EntrySpan> spanOf(String entryNo) {
        return spans.stream().filter(s -> s.entry().no().equals(entryNo)).findFirst();
    }

    public static Layout plan(Manifest manifest, Map<String, byte[]> sources) {
        List<Entry> packable = manifest.entries().stream().filter(Entry::isPackable).toList();
        int tocPages = Math.max(1, (packable.size() + TOC_ROWS_PER_PAGE - 1) / TOC_ROWS_PER_PAGE);
        List<EntrySpan> spans = new ArrayList<>();
        int cursor = COVER_PAGES + tocPages + 1;
        for (Entry entry : packable) {
            int count = 0;
            for (FileRef ref : entry.orderedFiles()) {
                int pages = PdfSupport.pageCount(ref.uri(), sources.get(ref.uri()));
                count += PdfSupport.selectedIndices(ref.uri(), pages, ref).size();
            }
            if (count == 0) {
                continue;
            }
            spans.add(new EntrySpan(entry, cursor, cursor + count - 1));
            cursor += count;
        }
        return new Layout(tocPages, spans);
    }
}
