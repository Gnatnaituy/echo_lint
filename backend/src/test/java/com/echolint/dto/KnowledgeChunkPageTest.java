package com.echolint.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 索引明细响应契约：字段名严格为前端约定的驼峰（前端并行开发，改名即破坏契约），
 * LocalDateTime 经 Jackson 序列化为 ISO 字符串；无分块时 embeddingModel/Dim 为 null。
 */
class KnowledgeChunkPageTest {

    private static final List<String> PAGE_FIELDS = List.of(
            "entryId", "title", "indexStatus", "indexError", "indexedAt", "embeddingHash",
            "embeddingModel", "embeddingDimensions", "total", "page", "size", "items");
    private static final List<String> ITEM_FIELDS = List.of(
            "chunkId", "chunkIndex", "content", "contentHash", "embeddingModel", "embeddingDim",
            "createdAt", "updatedAt");

    private static ObjectMapper mapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void serializesCamelCaseContractFieldsAndIsoTimes() throws Exception {
        LocalDateTime time = LocalDateTime.of(2026, 10, 23, 12, 0);
        KnowledgeChunkPage page = new KnowledgeChunkPage(12L, "话术规范", "READY", null, time,
                "entry-hash", "qwen3.7-text-embedding", 1024, 7, 0, 10,
                List.of(new KnowledgeChunkView(1L, 0, "不要辱骂客户", "chunk-hash",
                        "qwen3.7-text-embedding", 1024, time, time)));

        JsonNode json = mapper().readTree(mapper().writeValueAsString(page));

        assertEquals(new TreeSet<>(PAGE_FIELDS), new TreeSet<>(fieldNames(json)),
                "响应字段名必须与前端契约完全一致");
        assertEquals(12L, json.get("entryId").asLong());
        assertEquals("话术规范", json.get("title").asText());
        assertEquals("READY", json.get("indexStatus").asText());
        assertTrue(json.get("indexError").isNull());
        assertEquals("2026-10-23T12:00:00", json.get("indexedAt").asText());
        assertEquals("entry-hash", json.get("embeddingHash").asText());
        assertEquals("qwen3.7-text-embedding", json.get("embeddingModel").asText());
        assertEquals(1024, json.get("embeddingDimensions").asInt());
        assertEquals(7L, json.get("total").asLong());
        assertEquals(0, json.get("page").asInt());
        assertEquals(10, json.get("size").asInt());

        JsonNode item = json.get("items").get(0);
        assertEquals(new TreeSet<>(ITEM_FIELDS), new TreeSet<>(fieldNames(item)),
                "items 字段名必须与前端契约完全一致");
        assertEquals(1L, item.get("chunkId").asLong());
        assertEquals(0, item.get("chunkIndex").asInt());
        assertEquals("不要辱骂客户", item.get("content").asText());
        assertEquals("chunk-hash", item.get("contentHash").asText());
        assertEquals("qwen3.7-text-embedding", item.get("embeddingModel").asText());
        assertEquals(1024, item.get("embeddingDim").asInt());
        assertEquals("2026-10-23T12:00:00", item.get("createdAt").asText());
        assertEquals("2026-10-23T12:00:00", item.get("updatedAt").asText());
    }

    @Test
    void emptyChunksSerializeNullEmbeddingMetadata() throws Exception {
        KnowledgeChunkPage page = new KnowledgeChunkPage(404L, "空条目", "PENDING", null, null,
                null, null, null, 0, 0, 10, List.of());

        JsonNode json = mapper().readTree(mapper().writeValueAsString(page));

        assertTrue(json.get("embeddingModel").isNull());
        assertTrue(json.get("embeddingDimensions").isNull());
        assertTrue(json.get("indexedAt").isNull());
        assertTrue(json.get("embeddingHash").isNull());
        assertEquals(0, json.get("items").size());
    }
}
