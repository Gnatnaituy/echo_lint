package com.echolint.asr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.echolint.domain.TranscriptSegment;
import com.echolint.exception.BizException;
import com.echolint.service.TranscriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 百炼结果 JSON → {@link TranscriptionService.ChannelTranscript} 的映射（纯逻辑，不联网）： 毫秒 → 秒、duration
 * 来源、sentences 为空退化、speaker_id 兼容、错误信息提取。
 */
class DashScopeAsrProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DashScopeAsrProvider provider = new DashScopeAsrProvider(objectMapper);

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("测试 JSON 非法", e);
        }
    }

    @Test
    void mapsSentencesFromMillisecondsToSeconds() {
        JsonNode result =
                json(
                        """
                        {
                          "properties": { "original_duration_in_milliseconds": 26119, "channels": [0] },
                          "transcripts": [{
                            "channel_id": 0,
                            "text": "你好，请问有什么可以帮您。谢谢。",
                            "sentences": [
                              { "begin_time": 440, "end_time": 4720, "text": "你好，请问有什么可以帮您。",
                                "sentence_id": 1, "words": [{ "begin_time": 440, "end_time": 700, "text": "你好" }] },
                              { "begin_time": 5000, "end_time": 6300, "text": " 谢谢。 ", "sentence_id": 2 }
                            ]
                          }]
                        }
                        """);

        TranscriptionService.ChannelTranscript transcript =
                provider.mapResult(result, List.of("zh"));

        assertEquals("你好，请问有什么可以帮您。谢谢。", transcript.text());
        assertEquals("zh", transcript.language());
        assertEquals(26, transcript.durationSeconds(), "26119ms 四舍五入为 26s");
        assertEquals(2, transcript.segments().size());

        TranscriptSegment first = transcript.segments().get(0);
        assertEquals(0.44, first.start(), 1e-9);
        assertEquals(4.72, first.end(), 1e-9);
        assertEquals("你好，请问有什么可以帮您。", first.text());
        assertNull(first.speaker());
        assertNull(first.channel());

        TranscriptSegment second = transcript.segments().get(1);
        assertEquals(5.0, second.start(), 1e-9);
        assertEquals(6.3, second.end(), 1e-9);
        assertEquals("谢谢。", second.text(), "句子文本应 trim");
    }

    @Test
    void languageComesFromFirstLanguageHint() {
        JsonNode result = json("{\"transcripts\":[{\"text\":\"hi\",\"sentences\":[]}]}");

        assertEquals("en", provider.mapResult(result, List.of("en", "zh")).language());
        assertEquals("", provider.mapResult(result, List.of()).language());
        assertEquals("", provider.mapResult(result, null).language());
    }

    @Test
    void emptySentencesFallBackToSingleSegmentCoveringDuration() {
        JsonNode result =
                json(
                        """
                        {
                          "properties": { "original_duration_in_milliseconds": 5000 },
                          "transcripts": [{ "text": "整段文本", "sentences": [] }]
                        }
                        """);

        TranscriptionService.ChannelTranscript transcript =
                provider.mapResult(result, List.of("zh"));

        assertEquals(1, transcript.segments().size());
        TranscriptSegment segment = transcript.segments().get(0);
        assertEquals(0.0, segment.start(), 1e-9);
        assertEquals(5.0, segment.end(), 1e-9);
        assertEquals("整段文本", segment.text());
        assertEquals(5, transcript.durationSeconds());
    }

    @Test
    void durationFallsBackToLastSentenceEndWhenPropertiesMissing() {
        JsonNode result =
                json(
                        """
                        {
                          "transcripts": [{
                            "text": "第一句。第二句。",
                            "sentences": [
                              { "begin_time": 0, "end_time": 4000, "text": "第一句。" },
                              { "begin_time": 4000, "end_time": 7500, "text": "第二句。" }
                            ]
                          }]
                        }
                        """);

        TranscriptionService.ChannelTranscript transcript =
                provider.mapResult(result, List.of("zh"));

        assertEquals(8, transcript.durationSeconds(), "缺失 properties 时用最后一句 end（7.5s 四舍五入）");
        assertEquals(2, transcript.segments().size());
    }

    @Test
    void speakerIdIsToleratedButNotPropagated() {
        JsonNode result =
                json(
                        """
                        {
                          "properties": { "original_duration_in_milliseconds": 3000 },
                          "transcripts": [{
                            "text": "hello world",
                            "sentences": [
                              { "begin_time": 0, "end_time": 3000, "text": "hello world", "speaker_id": 2 }
                            ]
                          }]
                        }
                        """);

        TranscriptionService.ChannelTranscript transcript =
                provider.mapResult(result, List.of("zh"));

        assertEquals(1, transcript.segments().size());
        // 本 provider 固定 diarization_enabled=false，speaker 由双声道编排层补齐，这里保持 null
        assertNull(transcript.segments().get(0).speaker());
        assertEquals("hello world", transcript.segments().get(0).text());
    }

    @Test
    void blankSentenceTextIsSkipped() {
        JsonNode result =
                json(
                        """
                        {
                          "properties": { "original_duration_in_milliseconds": 9000 },
                          "transcripts": [{
                            "text": "有效文本",
                            "sentences": [
                              { "begin_time": 0, "end_time": 1000, "text": "   " },
                              { "begin_time": 1000, "end_time": 2000, "text": "有效文本" }
                            ]
                          }]
                        }
                        """);

        TranscriptionService.ChannelTranscript transcript =
                provider.mapResult(result, List.of("zh"));

        assertEquals(1, transcript.segments().size());
        assertEquals("有效文本", transcript.segments().get(0).text());
    }

    @Test
    void emptyOrMissingTranscriptsAreTolerated() {
        TranscriptionService.ChannelTranscript empty =
                provider.mapResult(json("{}"), List.of("zh"));
        assertEquals("", empty.text());
        assertEquals(0, empty.durationSeconds());
        assertTrue(empty.segments().isEmpty());

        TranscriptionService.ChannelTranscript noTranscripts =
                provider.mapResult(json("{\"transcripts\":[]}"), List.of("zh"));
        assertEquals("", noTranscripts.text());
        assertTrue(noTranscripts.segments().isEmpty());
    }

    @Test
    void taskFailureErrorIsExtractedFromOutputResults() {
        JsonNode failed =
                json(
                        """
                        {
                          "output": {
                            "task_id": "abc",
                            "task_status": "FAILED",
                            "results": [{ "code": "InvalidFile.DownloadFailed", "message": "下载文件失败" }]
                          }
                        }
                        """);

        assertEquals("InvalidFile.DownloadFailed: 下载文件失败", provider.describeError(failed));
    }

    @Test
    void httpErrorIsExtractedFromRootCodeAndMessage() {
        JsonNode error =
                json(
                        """
                        { "code": "InvalidApiKey", "message": "Invalid API-key provided.", "request_id": "r1" }
                        """);

        String message = provider.describeError(error);
        assertTrue(message.contains("InvalidApiKey"), message);
        assertTrue(message.contains("Invalid API-key provided."), message);
        assertFalse(message.isBlank());
    }

    // ------------------------------------------------------------------ 凭证不泄露（I3）

    @Test
    void errorMessagesNeverLeakTemporaryOssCredentials() {
        String body =
                """
                {
                  "code": "InvalidApiKey",
                  "message": "Invalid API-key provided.",
                  "request_id": "req-1",
                  "policy": "SECRET_POLICY_VALUE",
                  "signature": "SECRET_SIGNATURE_VALUE",
                  "oss_access_key_id": "SECRET_AK_VALUE",
                  "upload_host": "https://oss-cn-beijing.aliyuncs.com",
                  "x_oss_object_acl": "private"
                }
                """;

        String message = provider.describeErrorBody(body);

        assertTrue(message.contains("InvalidApiKey"), message);
        assertTrue(message.contains("Invalid API-key provided."), message);
        assertTrue(message.contains("req-1"), message);
        assertFalse(message.contains("SECRET_POLICY_VALUE"), message);
        assertFalse(message.contains("SECRET_SIGNATURE_VALUE"), message);
        assertFalse(message.contains("SECRET_AK_VALUE"), message);
        String lower = message.toLowerCase();
        assertFalse(lower.contains("signature"), message);
        assertFalse(lower.contains("policy"), message);
        assertFalse(lower.contains("oss_access_key_id"), message);
        assertFalse(lower.contains("upload_host"), message);
    }

    @Test
    void nonJsonErrorBodiesAreNeverEchoed() {
        String raw =
                "HTTP 401: signature=SECRET_SIG&policy=SECRET_POLICY&oss_access_key_id=SECRET_AK";

        String message = provider.describeErrorBody(raw);

        assertFalse(message.contains("SECRET_SIG"), message);
        assertFalse(message.contains("SECRET_POLICY"), message);
        assertFalse(message.contains("SECRET_AK"), message);
        String lower = message.toLowerCase();
        assertFalse(lower.contains("signature"), message);
        assertFalse(lower.contains("policy"), message);
        assertFalse(lower.contains("oss_access_key_id"), message);
    }

    @Test
    void uploadPolicyMissingFieldOnlyReportsFieldNameNotOtherCredentials() {
        JsonNode resp =
                json(
                        """
                        {
                          "data": {
                            "policy": "SECRET_POLICY_VALUE",
                            "signature": "SECRET_SIGNATURE_VALUE",
                            "oss_access_key_id": "SECRET_AK_VALUE",
                            "upload_dir": "asr-test"
                          }
                        }
                        """);

        BizException error =
                assertThrows(BizException.class, () -> provider.parseUploadPolicy(resp));

        assertTrue(error.getMessage().contains("upload_host"), error.getMessage());
        assertFalse(error.getMessage().contains("SECRET_POLICY_VALUE"), error.getMessage());
        assertFalse(error.getMessage().contains("SECRET_SIGNATURE_VALUE"), error.getMessage());
        assertFalse(error.getMessage().contains("SECRET_AK_VALUE"), error.getMessage());
        String lower = error.getMessage().toLowerCase();
        assertFalse(lower.contains("signature"), error.getMessage());
        assertFalse(lower.contains("policy"), error.getMessage());
        assertFalse(lower.contains("oss_access_key_id"), error.getMessage());
    }

    @Test
    void uploadPolicyIsParsedWhenAllFieldsPresent() {
        JsonNode resp =
                json(
                        """
                        {
                          "data": {
                            "policy": "p",
                            "signature": "s",
                            "upload_host": "https://oss-cn-beijing.aliyuncs.com",
                            "upload_dir": "asr-test",
                            "oss_access_key_id": "ak",
                            "x_oss_object_acl": "private",
                            "x_oss_forbid_overwrite": "true",
                            "max_file_size_mb": 200
                          }
                        }
                        """);

        DashScopeAsrProvider.UploadPolicy policy = provider.parseUploadPolicy(resp);

        assertEquals("https://oss-cn-beijing.aliyuncs.com", policy.uploadHost());
        assertEquals("asr-test", policy.uploadDir());
        assertEquals(200, policy.maxFileSizeMb());
    }

    @Test
    void ossErrorBodyOnlyExposesWhitelistedTags() {
        String xml =
                """
                <Error>
                  <Code>AccessDenied</Code>
                  <Message>Request has expired.</Message>
                  <RequestId>REQ-1</RequestId>
                  <HostId>SECRET_HOST_ID</HostId>
                  <OSSAccessKeyId>SECRET_AK_VALUE</OSSAccessKeyId>
                </Error>
                """;

        String message = provider.describeOssErrorBody(xml);

        assertTrue(message.contains("AccessDenied"), message);
        assertTrue(message.contains("Request has expired."), message);
        assertTrue(message.contains("REQ-1"), message);
        assertFalse(message.contains("SECRET_HOST_ID"), message);
        assertFalse(message.contains("SECRET_AK_VALUE"), message);
        String lower = message.toLowerCase();
        assertFalse(lower.contains("hostid"), message);
        assertFalse(lower.contains("ossaccesskeyid"), message);
    }
}
