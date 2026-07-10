package com.youzheng.huicui.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 录音管线的纯逻辑层。**这是 M-A2 唯一能被机器证明的部分**——
 * 「真机上能不能读到 OEM 录音目录」必须人肉验，但「读到之后怎么匹配」不该靠手测。
 */
class RecordingCoreTest {

    private val cn = ZoneId.of("Asia/Shanghai")

    // ── 号码归一化 ──────────────────────────────────────────────────────────

    @Test
    fun `国际前缀与分隔符都要去掉`() {
        assertEquals("13900000099", PhoneNumbers.normalize("+8613900000099"))
        assertEquals("13900000099", PhoneNumbers.normalize("008613900000099"))
        assertEquals("13900000099", PhoneNumbers.normalize("086-139 0000 0099"))
        assertEquals("13900000099", PhoneNumbers.normalize("139-0000-0099"))
        assertEquals("13900000099", PhoneNumbers.normalize("张三(13900000099)"))
    }

    @Test
    fun `短号不足11位时原样保留 绝不左填充`() {
        // 若粗暴 takeLast(11) 再左补零，10086 会和别的号撞
        assertEquals("10086", PhoneNumbers.normalize("10086"))
        assertEquals("110", PhoneNumbers.normalize("110"))
    }

    @Test
    fun `座机归一化到11位仍可比较`() {
        assertEquals("01012345678", PhoneNumbers.normalize("010-1234 5678"))
    }

    @Test
    fun `无数字返回空串 且空串不等于任何号码`() {
        assertEquals("", PhoneNumbers.normalize("通话录音"))
        assertEquals("", PhoneNumbers.normalize(null))
        assertFalse(PhoneNumbers.sameNumber("", ""))
        assertFalse(PhoneNumbers.sameNumber(null, "13900000099"))
    }

    @Test
    fun `不会把86开头的短串误当国际前缀砍掉`() {
        assertEquals("8613800", PhoneNumbers.normalize("8613800"))
    }

    // ── 文件名解析（各 ROM 真实模式，PRD 第 3_1 节）────────────────────────

    @Test
    fun `华为 号码_yyyyMMddHHmmss`() {
        val p = RecordingFileName.parse("13800138000_20260709103000.m4a", cn)
        assertEquals("13800138000", p.phone)
        assertEquals(epoch("2026-07-09T10:30:00", cn), p.recordedAtMillis)
    }

    @Test
    fun `小米 通话录音_联系人(号码)_时间戳`() {
        val p = RecordingFileName.parse("通话录音_张三(13900000099)_20260709_10_30_00.mp3", cn)
        assertEquals("13900000099", p.phone)
        assertEquals(epoch("2026-07-09T10:30:00", cn), p.recordedAtMillis)
    }

    @Test
    fun `vivo 号码(联系人)_日期 wav`() {
        val p = RecordingFileName.parse("13900000099(李四)_2026-07-09 10-30-00.wav", cn)
        assertEquals("13900000099", p.phone)
        assertEquals(epoch("2026-07-09T10:30:00", cn), p.recordedAtMillis)
    }

    @Test
    fun `文件名无号码时 phone 为 null 而不是瞎猜`() {
        val p = RecordingFileName.parse("录音 2026-07-09 10-30-00.amr", cn)
        assertNull(p.phone)
        assertEquals(epoch("2026-07-09T10:30:00", cn), p.recordedAtMillis)
    }

    @Test
    fun `不从长时间戳里截出假号码`() {
        // 20260709103000 里含 "1031..." 之类的子串，但两侧都是数字，不该被当成手机号
        val p = RecordingFileName.parse("record_20260709103000.m4a", cn)
        assertNull(p.phone)
    }

    @Test
    fun `14位时间戳不会被前8位吃掉`() {
        val p = RecordingFileName.parse("call_20260709103000.mp3", cn)
        assertEquals(epoch("2026-07-09T10:30:00", cn), p.recordedAtMillis)
    }

    @Test
    fun `未知格式两项都为 null 由上层退化处理`() {
        val p = RecordingFileName.parse("weird-file.bin", cn)
        assertNull(p.phone)
        assertNull(p.recordedAtMillis)
    }

    @Test
    fun `只接受能被 ASR 吃下的容器`() {
        assertTrue(RecordingFileName.isSupportedAudio("a.m4a"))
        assertTrue(RecordingFileName.isSupportedAudio("a.MP3"))
        assertTrue(RecordingFileName.isSupportedAudio("a.amr"))
        assertFalse(RecordingFileName.isSupportedAudio("a.tmp"))
        assertFalse(RecordingFileName.isSupportedAudio("a"))
    }

    // ── 匹配（最高原则：宁可问不可错挂）────────────────────────────────────

    private val t0 = epoch("2026-07-09T10:30:00", cn)

    /** 文件名时间按设备时区解析；这里固定东八区，让用例不受跑测机器的时区影响。 */
    private val cfg = MatchConfig(zone = cn)
    private fun session(id: String, caseId: String, num: String, start: Long, end: Long?) =
        CallSession(id, caseId, num, start, end)

    private fun file(name: String, lm: Long) = RecordingCandidate("/x/$name", name, lm, 1024)

    @Test
    fun `号码一致且在时间窗内 唯一匹配`() {
        val s = session("c1", "7", "13900000099", t0, t0 + 90_000)
        val f = file("13900000099_20260709103005.m4a", t0 + 95_000)
        val r = RecordingMatcher.match(f, listOf(s), cfg)
        assertTrue(r is MatchResult.Matched)
        assertEquals("c1", (r as MatchResult.Matched).session.callId)
    }

    @Test
    fun `号码与所有去电都不一致 判为无关文件 不碰用户自己的录音`() {
        val s = session("c1", "7", "13900000099", t0, t0 + 90_000)
        val f = file("13712345678_20260709103005.m4a", t0 + 95_000)
        val r = RecordingMatcher.match(f, listOf(s), cfg)
        assertTrue(r is MatchResult.Unrelated)
    }

    @Test
    fun `号码一致但时间窗外 交给用户确认 而不是直接挂上去`() {
        val s = session("c1", "7", "13900000099", t0, t0 + 60_000)
        val f = file("13900000099_20260709120000.m4a", epoch("2026-07-09T12:00:00", cn))
        val r = RecordingMatcher.match(f, listOf(s), cfg)
        assertTrue("号码是强证据但时间对不上，必须问", r is MatchResult.NeedsConfirmation)
    }

    @Test
    fun `文件名无号码 时间窗内唯一 可以直接匹配`() {
        val s = session("c1", "7", "13900000099", t0, t0 + 90_000)
        val f = file("录音.amr", t0 + 100_000)
        val r = RecordingMatcher.match(f, listOf(s), cfg)
        assertTrue(r is MatchResult.Matched)
    }

    @Test
    fun `文件名无号码 时间窗内两通且时间接近 交给用户二选一`() {
        val a = session("c1", "7", "13900000099", t0, t0 + 30_000)
        val b = session("c2", "8", "13712345678", t0 + 35_000, t0 + 60_000)
        val f = file("录音.amr", t0 + 62_000)   // 距 b 的区间 2s，距 a 的区间 32s → 差 30s > 15s
        val r = RecordingMatcher.match(f, listOf(a, b), cfg)
        assertTrue(r is MatchResult.Matched)
        assertEquals("c2", (r as MatchResult.Matched).session.callId)
    }

    @Test
    fun `两通难分伯仲时 宁可问不可错挂`() {
        // 文件正好落在两通之间，到两者距离几乎相等
        val a = session("c1", "7", "13900000099", t0, t0 + 10_000)
        val b = session("c2", "8", "13712345678", t0 + 20_000, t0 + 30_000)
        val f = file("录音.amr", t0 + 15_000)   // 距 a 结束 5s，距 b 开始 5s
        val r = RecordingMatcher.match(f, listOf(a, b), cfg)
        assertTrue(r is MatchResult.NeedsConfirmation)
        assertEquals(2, (r as MatchResult.NeedsConfirmation).sessions.size)
    }

    @Test
    fun `同号码连续拨打 取时间就近者`() {
        val a = session("c1", "7", "13900000099", t0, t0 + 20_000)
        val b = session("c2", "7", "13900000099", t0 + 120_000, t0 + 200_000)
        val f = file("13900000099_20260709103320.m4a", epoch("2026-07-09T10:33:20", cn))  // t0+200s
        val r = RecordingMatcher.match(f, listOf(a, b), cfg)
        assertEquals("c2", (r as MatchResult.Matched).session.callId)
    }

    @Test
    fun `没有任何会话时 一切文件都无关`() {
        val r = RecordingMatcher.match(file("13900000099_20260709103005.m4a", t0), emptyList(), cfg)
        assertTrue(r is MatchResult.Unrelated)
    }

    @Test
    fun `通话尚未结束时用开放窗口 不至于把刚落盘的录音判为无关`() {
        val s = session("c1", "7", "13900000099", t0, null)
        val f = file("13900000099_20260709103005.m4a", t0 + 5_000)
        assertTrue(RecordingMatcher.match(f, listOf(s), cfg) is MatchResult.Matched)
    }

    @Test
    fun `批量匹配 一个会话只挂一个文件`() {
        val a = session("c1", "7", "13900000099", t0, t0 + 20_000)
        val b = session("c2", "8", "13712345678", t0 + 60_000, t0 + 90_000)
        val f1 = file("13900000099_20260709103010.m4a", t0 + 10_000)
        val f2 = file("13712345678_20260709103115.m4a", t0 + 75_000)
        val results = RecordingMatcher.matchAll(listOf(f2, f1), listOf(a, b), cfg)
        val matched = results.filterIsInstance<MatchResult.Matched>()
        assertEquals(2, matched.size)
        assertEquals(setOf("c1", "c2"), matched.map { it.session.callId }.toSet())
    }

    // ── 接通判定（BR-APP-05：未接通不上传）────────────────────────────────

    private val someFile = RecordingCandidate("/x/a.m4a", "a.m4a", 0, 1)

    @Test
    fun `有录音就上传 哪怕读不到通话记录`() {
        val r = CallOutcomeDecider.decide(callLogDurationSec = null, candidate = someFile)
        assertTrue(r is CallOutcome.Uploadable)
        assertEquals(0, (r as CallOutcome.Uploadable).durationSec)
    }

    @Test
    fun `未接通不上传 —— 不浪费 ASR 分钟也不污染话术飞轮`() {
        assertTrue(CallOutcomeDecider.decide(0, null) is CallOutcome.NotConnected)
        assertTrue(CallOutcomeDecider.decide(1, null) is CallOutcome.NotConnected)
    }

    @Test
    fun `接通了却没录音 —— 提示手动上传 而不是当作未接通吞掉`() {
        val r = CallOutcomeDecider.decide(95, null)
        assertTrue(r is CallOutcome.RecordingMissing)
        assertEquals(95, (r as CallOutcome.RecordingMissing).durationSec)
    }

    @Test
    fun `既无录音也无通话记录 判未接通并说明原因`() {
        val r = CallOutcomeDecider.decide(null, null)
        assertTrue(r is CallOutcome.NotConnected)
        assertTrue((r as CallOutcome.NotConnected).reason.contains("READ_CALL_LOG"))
    }

    // ── 上传策略 ────────────────────────────────────────────────────────────

    @Test
    fun `退避 1_2_4_8 分钟 封顶 30 分钟`() {
        assertEquals(60_000L, UploadPolicy.backoffMillis(1))
        assertEquals(120_000L, UploadPolicy.backoffMillis(2))
        assertEquals(240_000L, UploadPolicy.backoffMillis(3))
        assertEquals(480_000L, UploadPolicy.backoffMillis(4))
        assertEquals(30 * 60_000L, UploadPolicy.backoffMillis(9))
        assertEquals(30 * 60_000L, UploadPolicy.backoffMillis(100))   // 不溢出
    }

    @Test
    fun `重试耗尽转失败待手动`() {
        assertFalse(UploadPolicy.shouldGiveUp(7))
        assertTrue(UploadPolicy.shouldGiveUp(8))
    }

    @Test
    fun `半截文件不入队 —— 大小连续三次不变才算写完`() {
        assertFalse(UploadPolicy.isFileStable(listOf(1024)))
        assertFalse(UploadPolicy.isFileStable(listOf(1024, 2048, 3072)))
        assertFalse(UploadPolicy.isFileStable(listOf(0, 0, 0)))        // 空文件不算稳定
        assertTrue(UploadPolicy.isFileStable(listOf(1024, 3072, 3072, 3072)))
    }

    @Test
    fun `上传成功满7天才删本地`() {
        val day = 24 * 3600 * 1000L
        assertFalse(UploadPolicy.shouldDeleteLocal(0, 6 * day))
        assertTrue(UploadPolicy.shouldDeleteLocal(0, 7 * day))
    }

    // ── 目录预设 ────────────────────────────────────────────────────────────

    @Test
    fun `按厂商给候选目录 且总是带上通用兜底`() {
        val xiaomi = RecordingDirectories.candidatesFor("Xiaomi")
        assertEquals("MIUI/sound_recorder/call_rec", xiaomi.first())
        assertTrue(xiaomi.containsAll(RecordingDirectories.FALLBACK_DIRS))
    }

    @Test
    fun `厂商名大小写不敏感`() {
        assertEquals(
            RecordingDirectories.profileFor("HUAWEI").vendorKey,
            RecordingDirectories.profileFor("huawei").vendorKey,
        )
    }

    @Test
    fun `原生 Android 明确标注没有系统通话录音能力`() {
        assertFalse(RecordingDirectories.profileFor("Google").hasSystemCallRecording)
    }

    @Test
    fun `未知厂商不假装认识 但仍给兜底目录`() {
        val p = RecordingDirectories.profileFor("SomeNewBrand")
        assertTrue(p.candidateDirs.isEmpty())
        assertEquals(RecordingDirectories.FALLBACK_DIRS, RecordingDirectories.candidatesFor("SomeNewBrand"))
    }

    private fun epoch(localIso: String, zone: ZoneId): Long =
        java.time.LocalDateTime.parse(localIso).atZone(zone).toInstant().toEpochMilli()
}
