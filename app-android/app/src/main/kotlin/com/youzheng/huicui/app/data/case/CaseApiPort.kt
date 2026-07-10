package com.youzheng.huicui.app.data.case

import com.youzheng.huicui.app.api.apis.CasesApi
import com.youzheng.huicui.app.api.models.CaseDetail
import com.youzheng.huicui.app.api.models.CasePage
import retrofit2.Response

/**
 * `CaseRepository` 只需要案件的两个读端点。抽出这个窄接口有两个好处：
 *   ① 离线读的单测可以给一个「永远抛 IOException」的假实现，不必去 mock 生成的 `CasesApi`
 *      （它有十几个方法，且签名随契约变）；
 *   ② 生成物的方法签名变了，只有这一个适配类需要改。
 */
interface CaseApiPort {
    suspend fun listCases(q: String?, page: Int, size: Int): Response<CasePage>
    suspend fun getCase(id: String): Response<CaseDetail>
}

class RetrofitCaseApiPort(private val api: CasesApi) : CaseApiPort {
    override suspend fun listCases(q: String?, page: Int, size: Int): Response<CasePage> =
        api.listCases(q = q, page = page, size = size)

    override suspend fun getCase(id: String): Response<CaseDetail> = api.getCase(id)
}
