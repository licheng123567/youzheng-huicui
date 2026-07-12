package com.youzheng.huicui.web;

import com.youzheng.huicui.error.ApiException;
import com.youzheng.huicui.error.BizError;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 录音音频的公开拉取端点（v1.24.0·security: []）。**只为百炼而开**：
 * 「录音文件识别」是异步任务——我们提交一个 URL，阿里侧主动来拉音频。而录音是 bytea 存在库里的，
 * 没有可拉取的地址，所以必须给一个。
 *
 * <p>录音是敏感个人信息，因此这个口收得很紧：
 * <ul>
 *   <li>token = 32 字节随机（不可猜、不可枚举），一录音一签；</li>
 *   <li><b>TTL 30 分钟</b>，转写一完成/失败就立刻删除凭据（AiPipelineService 里做的），链接当场失效；</li>
 *   <li>除音频字节本身外不暴露任何信息——没有案件号、没有业主、没有列表接口；</li>
 *   <li>token 无效/过期 → 404（不区分「不存在」与「过期」，不给枚举者任何信号）。</li>
 * </ul>
 *
 * <p>备选方案是走 WebSocket 实时协议把音频帧推上去（完全不开公开口），但协议复杂、断点重试难做。
 * 选了签名 URL：生产本就有公网域名（缴费链接一直在用）。
 */
@RestController
public class PublicRecordingController {

    private final JdbcTemplate jdbc;

    public PublicRecordingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/pub/recordings/{token}")
    public ResponseEntity<byte[]> getPublicRecordingAudio(@PathVariable String token) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT r.audio_bytes, r.audio_content_type"
                            + " FROM recording_pub_token t JOIN call_recording r ON r.id = t.recording_id"
                            + " WHERE t.token = ? AND t.expires_at > now()", token);
        } catch (Exception e) {
            throw new ApiException(BizError.NOT_FOUND_404, "音频不存在或链接已过期");
        }
        byte[] audio = (byte[]) row.get("audio_bytes");
        if (audio == null || audio.length == 0) {
            throw new ApiException(BizError.NOT_FOUND_404, "音频不存在或链接已过期");
        }
        String ct = (String) row.get("audio_content_type");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ct == null || ct.isBlank() ? "audio/wav" : ct)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(ct == null || ct.isBlank() ? "audio/wav" : ct))
                .body(audio);
    }
}
