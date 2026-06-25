package com.youzheng.huicui.web.dto;

/**
 * 对齐契约 schema LatestRecording（getLatestRecording 出参）。
 *   hasRecording: 最近一通是否有录音上来；
 *   recording: CallRecording | null；
 *   hint: 无录音时引导文案（BR-M4-01b：服务端不拉本机目录，只回最近一通状态）。
 */
public record LatestRecordingDto(
        boolean hasRecording,
        CallRecordingDto recording,
        String hint) {
}
