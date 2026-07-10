package com.youzheng.huicui.app

import android.content.Context
import androidx.room.Room
import com.youzheng.huicui.app.api.apis.AuthApi
import com.youzheng.huicui.app.api.apis.CasesApi
import com.youzheng.huicui.app.api.apis.DispatchApi
import com.youzheng.huicui.app.api.apis.NotificationApi
import com.youzheng.huicui.app.api.apis.OrgMemberApi
import com.youzheng.huicui.app.api.apis.WorkbenchApi
import com.youzheng.huicui.app.data.auth.AuthRepository
import com.youzheng.huicui.app.data.auth.EncryptedTokenStore
import com.youzheng.huicui.app.data.auth.PasswordRepository
import com.youzheng.huicui.app.data.auth.TokenStore
import com.youzheng.huicui.app.data.case.CaseRepository
import com.youzheng.huicui.app.data.case.RetrofitCaseApiPort
import com.youzheng.huicui.app.data.case.NotificationRepository
import com.youzheng.huicui.app.data.case.SeaRepository
import com.youzheng.huicui.app.data.case.WorkbenchRepository
import com.youzheng.huicui.app.data.db.HuicuiDb
import com.youzheng.huicui.app.data.net.AuthEdgeApi
import com.youzheng.huicui.app.data.net.RetrofitFactory
import com.youzheng.huicui.app.data.net.SessionListener
import com.youzheng.huicui.app.data.session.Session
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface SessionEvent {
    data object Unauthorized : SessionEvent
    data object MustChangePassword : SessionEvent
}

/**
 * 手写的 ServiceLocator，仍不引 Hilt。
 * M-A2 引入了 Room（因此也引入了 KSP），但对象图依旧只有十来个节点、零多绑定场景，
 * Hilt 换来的只是更多构建期活动件。等真需要按 build variant 换实现时再上。
 */
object ServiceLocator {

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents

    lateinit var tokenStore: TokenStore
        private set
    lateinit var session: Session
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var passwordRepository: PasswordRepository
        private set
    lateinit var caseRepository: CaseRepository
        private set
    lateinit var workbenchRepository: WorkbenchRepository
        private set
    lateinit var seaRepository: SeaRepository
        private set
    lateinit var notificationRepository: NotificationRepository
        private set

    fun init(context: Context) {
        if (::authRepository.isInitialized) return

        val app = context.applicationContext
        tokenStore = EncryptedTokenStore(app)
        session = Session()

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

        val db = Room.databaseBuilder(app, HuicuiDb::class.java, "huicui.db")
            // 缓存是可再生的派生数据：schema 变了直接丢弃重建，不值得写 migration。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        authRepository = AuthRepository(
            edge = retrofit.create(AuthEdgeApi::class.java),
            authApi = retrofit.create(AuthApi::class.java),
            tokenStore = tokenStore,
        )
        passwordRepository = PasswordRepository(retrofit.create(OrgMemberApi::class.java))
        caseRepository = CaseRepository(RetrofitCaseApiPort(retrofit.create(CasesApi::class.java)), db.caseDao())
        workbenchRepository = WorkbenchRepository(retrofit.create(WorkbenchApi::class.java))
        seaRepository = SeaRepository(retrofit.create(DispatchApi::class.java))
        notificationRepository = NotificationRepository(retrofit.create(NotificationApi::class.java))
    }

    /** 退出登录：令牌与内存中的主体信息都要清；缓存的案件属于上一个账号，绝不能留给下一个人看。 */
    suspend fun logout() {
        authRepository.logout()
        session.clear()
        runCatching { caseRepository.clearCache() }
    }
}
