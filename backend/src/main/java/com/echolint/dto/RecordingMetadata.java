package com.echolint.dto;

import com.echolint.domain.CallDirection;
import com.echolint.exception.BizException;
import com.echolint.util.TimeRanges;
import org.springframework.http.HttpStatus;

/**
 * 录音元数据（上传时可选填写）。全部可空，历史数据兼容。
 *
 * @param agentName  坐席名称，最长 64
 * @param teamName   团队名称，最长 128
 * @param callTime   通话时间；为空时统计/筛选回退 uploadTime
 * @param direction  通话方向，默认 UNKNOWN
 * @param businessLine 业务线，最长 64
 */
public record RecordingMetadata(
        String agentName,
        String teamName,
        java.time.LocalDateTime callTime,
        CallDirection direction,
        String businessLine) {

    public static final int MAX_AGENT_NAME = 64;
    public static final int MAX_TEAM_NAME = 128;
    public static final int MAX_BUSINESS_LINE = 64;

    public static final RecordingMetadata EMPTY =
            new RecordingMetadata(null, null, null, CallDirection.UNKNOWN, null);

    /**
     * 解析并校验上传参数。空白值归一为 null；超长/方向非法直接 400。
     */
    public static RecordingMetadata of(String agentName, String teamName, String callTime,
                                       String direction, String businessLine) {
        return new RecordingMetadata(
                trimToNull(agentName, MAX_AGENT_NAME, "坐席名称"),
                trimToNull(teamName, MAX_TEAM_NAME, "团队名称"),
                TimeRanges.parseDateTime(callTime),
                CallDirection.parse(direction),
                trimToNull(businessLine, MAX_BUSINESS_LINE, "业务线"));
    }

    private static String trimToNull(String raw, int maxLength, String fieldLabel) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw new BizException(fieldLabel + "最长 " + maxLength + " 个字符", HttpStatus.BAD_REQUEST);
        }
        return value;
    }
}
