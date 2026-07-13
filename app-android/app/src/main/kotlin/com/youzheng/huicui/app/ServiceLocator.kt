package com.youzheng.huicui.app

import android.content.Context
import androidx.room.Room
import com.youzheng.huicui.app.api.apis.AiApi
import com.youzheng.huicui.app.api.apis.AuthApi
import com.youzheng.huicui.app.api.apis.CasesApi
import com.youzheng.huicui.app.data.playbook.PlaybookRepository
import com.youzheng.huicui.app.ui.common.clearCaseSummaryMemo
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
import com.youzheng.huicui.app.api.apis.CollectionApi
import com.youzheng.huicui.app.api.apis.EvidenceApi
import com.youzheng.huicui.app.data.delivery.DeliveryApiPort
import com.youzheng.huicui.app.data.delivery.DeliverySubmitter
import com.youzheng.huicui.app.data.delivery.DeliveryUploadApi
import com.youzheng.huicui.app.data.delivery.PhotoCompressor
import com.youzheng.huicui.app.data.delivery.RetrofitDeliveryApiPort
import com.youzheng.huicui.app.data.db.HuicuiDb
import com.youzheng.huicui.app.data.db.MIGRATION_1_2
import com.youzheng.huicui.app.data.net.RecordingUploadApi
import com.youzheng.huicui.app.recording.AppSettings
import com.youzheng.huicui.app.recording.LocalRecordingStore
import com.youzheng.huicui.app.recording.RecordingRepository
import com.youzheng.huicui.app.recording.RetrofitRecordingUploadPort
import com.youzheng.huicui.app.recording.UploadScheduler
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

    lateinit var playbookRepository: PlaybookRepository
        private set
    lateinit var recordingRepository: RecordingRepository
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var deliverySubmitter: DeliverySubmitter
        private set
    lateinit var photoCompressor: PhotoCompressor
        private set

    val isInitialized: Boolean get() = ::recordingRepository.isInitialized

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

        // v2 起**不能**再用毁灭式迁移：upload_item 里躺着还没传上去的通话录音，
        // 丢了就永远找不回来（服务端也没有）。cached_case 丢了倒无所谓，但它俩在同一个库里。
        val db = Room.databaseBuilder(app, HuicuiDb::class.java, "huicui.db")
            .addMigrations(MIGRATION_1_2)
            .build()

        settings = AppSettings(app)
        settings.restore()

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
        playbookRepository = PlaybookRepository(retrofit.create(AiApi::class.java))
        recordingRepository = RecordingRepository(
            sessions = db.callSessionDao(),
            uploads = db.uploadDao(),
            port = RetrofitRecordingUploadPort(retrofit.create(RecordingUploadApi::class.java)),
            collectionApi = retrofit.create(CollectionApi::class.java),
            store = LocalRecordingStore(app),
        )

        val deliveryPort: DeliveryApiPort = RetrofitDeliveryApiPort(
            upload = retrofit.create(DeliveryUploadApi::class.java),
            collection = retrofit.create(CollectionApi::class.java),
            evidence = retrofit.create(EvidenceApi::class.java),
        )
        deliverySubmitter = DeliverySubmitter(deliveryPort)
        photoCompressor = PhotoCompressor(app)

        // 兜底：FileObserver 被 ROM 杀掉、上传一直失败时，靠周期任务把队列推完
        UploadScheduler.schedulePeriodic(app)
    }

    /**
     * 退出登录：令牌、主体信息、案件缓存**以及未上传的录音队列**都要清 ——
     * 它们属于上一个账号的案件，换人登录后既传不上去（403），也不该留在这台手机上。
     * 队列非空时界面必须先警示用户（[com.youzheng.huicui.app.ui.me.MeScreen]）。
     */
    suspend fun logout() {
        authRepository.logout()
        session.clear()
        runCatching { caseRepository.clearCache() }
        runCatching { recordingRepository.clearOnLogout() }
        // 界面层的案件摘要（业主名/小区/房号）也是上一个账号的数据，别留给下一个登录的人
        clearCaseSummaryMemo()
    }
}
