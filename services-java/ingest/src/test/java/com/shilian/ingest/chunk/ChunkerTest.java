package com.shilian.ingest.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.ingest.parse.PageText;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    @Test
    void slidingWindowWithOverlap() {
        String text = "0123456789".repeat(3);
        List<Chunk> chunks = new Chunker(10, 3).split(List.of(new PageText(1, text)));
        assertThat(chunks).extracting(Chunk::text)
                .containsExactly("0123456789", "7890123456", "4567890123", "123456789");
        assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1, 2, 3);
        assertThat(chunks).allMatch(c -> c.page() == 1);
    }

    @Test
    void chunksNeverCrossPagesAndKeepPageNumbers() {
        List<Chunk> chunks = new Chunker(100, 10).split(List.of(
                new PageText(1, "第一页内容"), new PageText(2, "  \n "), new PageText(3, "第三页内容")));
        assertThat(chunks).extracting(Chunk::page).containsExactly(1, 3);
        assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
    }

    @Test
    void prefersSentenceBoundaryNearWindowEnd() {
        String text = "一二三四五六七。八九十甲乙丙丁戊己庚";
        List<Chunk> chunks = new Chunker(10, 0).split(List.of(new PageText(1, text)));
        assertThat(chunks.get(0).text()).isEqualTo("一二三四五六七。");
        assertThat(chunks.get(1).text()).startsWith("八九十");
    }

    @Test
    void normalizesWhitespace() {
        assertThat(Chunker.normalize("a  \t\r\nb\n\n\n\nc ")).isEqualTo("a\nb\n\nc");
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new Chunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Chunker(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
