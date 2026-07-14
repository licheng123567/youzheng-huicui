package com.youzheng.huicui.app.data.playbook

import com.youzheng.huicui.app.api.apis.AiApi
import com.youzheng.huicui.app.api.models.Playbook
import java.io.IOException

/**
 * 作战手册读取（BR-M5-05）。
 *
 * **为什么不能只读 CaseDetail.playbook**：契约里 `CaseDetail` 是有 playbook 字段，
 * 但后端 M2 读阶段**恒返回 null**（CasesM2Controller「案件页经 /projects/{id}/playbook 容错取底稿」）。
 * 只认那个字段的话，手册在手机上永远是空的。网页端（CaseThreeColumn.vue）就是这么容错的，这里同构：
 *
 *   CaseDetail.playbook（将来后端填上就直接用）→ 项目级 /projects/{id}/playbook → 批次级 /batches/{id}/playbook
 *
 * 批次可以自定义手册（playbookMode=CUSTOM）而不继承项目，所以批次级不是多余的一跳。
 *
 * 读手册**不需要权限点**（range scope）：催收员和物业协调员都看得到，
 * 但服务商/催收员只会拿到已发布（PUBLISHED）的版本 —— 那是后端裁的，客户端不用管。
 */
class PlaybookRepository(private val ai: AiApi) {

    suspend fun forCase(projectId: String?, batchId: String?): Playbook? {
        // **批次优先**。契约 /batches/{id}/playbook 写死了「批次自定义 > 项目级（BR-M2-18b）」，
        // 且该端点返回的已经是**生效**手册（source=CUSTOM 或 INHERITED），项目级已被它涵盖。
        // 反过来先问项目，一个 CUSTOM 批次（物业专门为这批高龄欠费户改过话术）就会被项目通用版盖掉 ——
        // 催收员照着一份已经被覆盖的话术开口。
        batchId?.takeIf { it.isNotBlank() }?.let { id ->
            fetch { ai.getBatchPlaybook(id).takeIf { it.isSuccessful }?.body()?.playbook }?.let { return it }
        }
        // 没有 batchId（理论上不该有）时才退回项目级
        projectId?.takeIf { it.isNotBlank() }?.let { id ->
            fetch { ai.getPlaybook(id).takeIf { it.isSuccessful }?.body() }?.let { return it }
        }
        return null
    }

    /** 手册拿不到不是错误——项目还没录手册是常态。静默降级，界面不显示这一节就是了。 */
    private inline fun <T> fetch(block: () -> T?): T? = try {
        block()
    } catch (e: IOException) {
        null
    }
}
