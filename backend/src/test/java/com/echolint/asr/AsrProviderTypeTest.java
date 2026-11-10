package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** provider 类型解析与契约元数据（宽松解析：非法值视为未设置）。 */
class AsrProviderTypeTest {

    @Test
    void fromValueIsLenientAndIgnoresCaseAndWhitespace() {
        assertEquals(AsrProviderType.LOCAL, AsrProviderType.fromValue("local"));
        assertEquals(AsrProviderType.LOCAL, AsrProviderType.fromValue("  LOCAL "));
        assertEquals(AsrProviderType.DASHSCOPE, AsrProviderType.fromValue("DashScope"));
        assertEquals(AsrProviderType.DASHSCOPE, AsrProviderType.fromValue("dashscope"));
    }

    @Test
    void fromValueReturnsNullForUnsetOrInvalidValues() {
        assertNull(AsrProviderType.fromValue(null));
        assertNull(AsrProviderType.fromValue(""));
        assertNull(AsrProviderType.fromValue("   "));
        assertNull(AsrProviderType.fromValue("whisper"));
        assertNull(AsrProviderType.fromValue("openai"));
    }

    @Test
    void metadataMatchesPersistenceContract() {
        assertEquals("local", AsrProviderType.LOCAL.value());
        assertEquals("本地 whisper.cpp", AsrProviderType.LOCAL.label());
        assertEquals("WHISPER_API_KEY", AsrProviderType.LOCAL.apiKeyEnv());

        assertEquals("dashscope", AsrProviderType.DASHSCOPE.value());
        assertEquals("阿里云百炼", AsrProviderType.DASHSCOPE.label());
        assertEquals("DASHSCOPE_API_KEY", AsrProviderType.DASHSCOPE.apiKeyEnv());
    }
}
