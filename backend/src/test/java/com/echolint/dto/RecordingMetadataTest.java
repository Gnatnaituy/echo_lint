package com.echolint.dto;

import com.echolint.domain.CallDirection;
import com.echolint.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上传元数据解析与校验：长度上限、方向枚举、通话时间格式、空白归一。
 */
class RecordingMetadataTest {

    @Test
    void blanksBecomeNullAndDirectionDefaultsToUnknown() {
        RecordingMetadata metadata = RecordingMetadata.of("  ", null, "", "  ", "");

        assertNull(metadata.agentName());
        assertNull(metadata.teamName());
        assertNull(metadata.callTime());
        assertNull(metadata.businessLine());
        assertEquals(CallDirection.UNKNOWN, metadata.direction());
    }

    @Test
    void parsesDirectionCaseInsensitivelyAndCallTimeInBothFormats() {
        RecordingMetadata metadata = RecordingMetadata.of(" 张三 ", "客服一组",
                "2026-10-20 09:30:00", "outbound", " 售后 ");

        assertEquals("张三", metadata.agentName());
        assertEquals("客服一组", metadata.teamName());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30), metadata.callTime());
        assertEquals(CallDirection.OUTBOUND, metadata.direction());
        assertEquals("售后", metadata.businessLine());
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 30),
                RecordingMetadata.of(null, null, "2026-10-20T09:30:00", null, null).callTime());
    }

    @Test
    void lengthLimitsAreEnforced() {
        assertDoesNotThrow(() -> RecordingMetadata.of("a".repeat(64), "b".repeat(128), null, null, "c".repeat(64)));

        BizException agentTooLong = assertThrows(BizException.class,
                () -> RecordingMetadata.of("a".repeat(65), null, null, null, null));
        assertEquals(400, agentTooLong.getStatus().value());
        assertTrue(agentTooLong.getMessage().contains("坐席名称"));

        assertThrows(BizException.class, () -> RecordingMetadata.of(null, "b".repeat(129), null, null, null));
        assertThrows(BizException.class, () -> RecordingMetadata.of(null, null, null, null, "c".repeat(65)));
    }

    @Test
    void invalidDirectionAndCallTimeAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RecordingMetadata.of(null, null, null, "SIDEWAYS", null));
        assertThrows(BizException.class,
                () -> RecordingMetadata.of(null, null, "2026/10/20", null, null));
    }
}
