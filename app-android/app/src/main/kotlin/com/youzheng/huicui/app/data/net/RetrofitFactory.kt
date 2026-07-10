package com.youzheng.huicui.app.data.net

import com.youzheng.huicui.app.api.infrastructure.Serializer
import com.youzheng.huicui.app.data.auth.TokenStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {

    fun create(
        baseUrl: String,
        tokenStore: TokenStore,
        listener: SessionListener,
        debug: Boolean,
    ): Retrofit {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)   // M-A2 上传录音
            .addInterceptor(AuthInterceptor(tokenStore, listener))
            .apply {
                if (debug) {
                    // 只在 debug 打日志：BODY 级别会打印 JWT 与口令
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()

        // 复用生成物的 Json 配置（含 OffsetDateTime/BigDecimal 等 contextual 适配器），
        // 保证手写接口与生成接口的序列化语义完全一致。
        val json = Serializer.kotlinxSerializationJson

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
