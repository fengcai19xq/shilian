package com.shilian.ingest.chunk;

import com.shilian.ingest.parse.PageText;
import java.util.ArrayList;
import java.util.List;

/**
 * 按字符数滑动窗口切块，相邻块重叠 overlap 个字符；块不跨页，保证 page 准确。
 * 窗口末尾 40% 范围内若有换行或句末标点，优先在那里断开，避免把句子切碎。
 */
public class Chunker {

    private static final String SOFT_BREAKS = "\n。！？；.!?;";

    private final int size;
    private final int overlap;

    public Chunker(int size, int overlap) {
        if (size <= 0) {
            throw new IllegalArgumentException("chunk size 必须 > 0");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("chunk overlap 必须满足 0 <= overlap < size");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public int size() {
        return size;
    }

    public int overlap() {
        return overlap;
    }

    public List<Chunk> split(List<PageText> pages) {
        List<Chunk> chunks = new ArrayList<>();
        for (PageText page : pages) {
            String text = normalize(page.text());
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + size, text.length());
                if (end < text.length()) {
                    end = softEnd(text, start, end);
                }
                String piece = text.substring(start, end).strip();
                if (!piece.isEmpty()) {
                    chunks.add(new Chunk(chunks.size(), page.page(), piece));
                }
                if (end >= text.length()) {
                    break;
                }
                start = Math.max(end - overlap, start + 1);
            }
        }
        return chunks;
    }

    private int softEnd(String text, int start, int end) {
        int floor = start + (int) Math.ceil(size * 0.6);
        for (int i = end - 1; i >= floor; i--) {
            if (SOFT_BREAKS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }
}
