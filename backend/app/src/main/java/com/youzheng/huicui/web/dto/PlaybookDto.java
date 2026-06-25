package com.youzheng.huicui.web.dto;

import java.util.List;

/**
 * 契约 Playbook（M5 作战手册）。projectId/version/content/adoptMode/adoptedBy/versions[]。
 *
 * 飞轮终环（BR-M5-05）：AI 产草稿状态 DRAFT，唯有物业采纳（adoptPlaybook）才转 PUBLISHED 发布给一线。
 * 可见性（BR-M5-05）：服务商/催收员只见已发布版；物业/平台见现行版 + 版本历史（含 DRAFT）。
 * 非平台/非物业主体若该项目无 PUBLISHED 版 → content=null（不暴露草稿）。
 *
 * adoptMode 取 PlaybookAdoptModeEnum：FORCE_MANUAL | LOW_RISK_AUTO（DDL chk_playbook_mode 一致）。
 */
public record PlaybookDto(
        String projectId,
        String version,
        String content,
        String adoptMode,
        String adoptedBy,
        List<PlaybookVersionDto> versions) {

    /** versions[] 元素：{version, source, by, tm}（契约 Playbook.versions.items）。 */
    public record PlaybookVersionDto(
            String version,
            String source,
            String by,
            String tm) {
    }
}
