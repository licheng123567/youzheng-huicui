package com.youzheng.huicui.app

import android.content.Context
import com.youzheng.huicui.app.api.apis.AuthApi
import com.youzheng.huicui.app.api.apis.OrgMemberApi
import com.youzheng.huicui.app.data.auth.AuthRepository
import com.youzheng.huicui.app.data.auth.EncryptedTokenStore
import com.youzheng.huicui.app.data.auth.PasswordRepository
import com.youzheng.huicui.app.data.auth.TokenStore
import com.youzheng.huicui.app.data.net.AuthEdgeApi
import com.youzheng.huicui.app.data.net.RetrofitFactory
import com.youzheng.huicui.app.data.net.SessionListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface SessionEvent {
    data object Unauthorized : SessionEvent
    data object MustChangePassword : SessionEvent
}

/**
 * M-A1 用手写的 ServiceLocator，不引 Hilt。
 * 理由：本阶段只有 4 个对象、零多绑定场景；Hilt 会带进 KSP，而 KSP 版本必须与 Kotlin 精确配对，
 * 是 CI 首跑最容易碎的地方。M-A2 引入 Room 时一并上 Hilt（那时确实需要）。
 */
object ServiceLocator {

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents

    lateinit var tokenStore: TokenStore
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var passwordRepository: PasswordRepository
        private set

    fun init(context: Context) {
        if (::authRepository.isInitialized) return

        tokenStore = EncryptedTokenStore(context.applicationContext)

        val listener = object : SessionListener {
            override fun onUnauthorized() { _sessionEvents.tryEmit(SessionEvent.Unauthorized) }
            override fun onMustChangePassword() { _sessionEvents.tryEmit(SessionEvent.MustChangePassword) }
        }

        val retrofit = RetrofitFactory.create(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenStore = tokenStore,
            listener = listener,
            debug = BuildConfig.DEBUG,
        )

        authRepository = AuthRepository(
            edge = retrofit.create(AuthEdgeApi::class.java),
            authApi = retrofit.create(AuthApi::class.java),
            tokenStore = tokenStore,
        )
        passwordRepository = PasswordRepository(retrofit.create(OrgMemberApi::class.java))
    }
}
