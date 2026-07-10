package com.youzheng.huicui.app.data.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 采样率是「宁大勿小」：解码出的图必须 ≥ 2560，再靠 JPEG quality 收体积，不能一步跳到更小（二次画质损失）。 */
class ImageShrinkTest {

    @Test
    fun `小图不采样`() {
        assertEquals(1, ImageShrink.sampleSize(1080, 1920))
        assertEquals(1, ImageShrink.sampleSize(2560, 2560))
    }

    @Test
    fun `主流手机原片 4000x3000 采样 1 档`() {
        // 4000/2 = 2000 < 2560 → 不能减半，保持 1（解码 4000 再由 quality 收）
        assertEquals(1, ImageShrink.sampleSize(4000, 3000))
    }

    @Test
    fun `超大图按 2 的幂逐级减半，且减完仍不小于 2560`() {
        assertEquals(2, ImageShrink.sampleSize(6000, 4000))    // 6000/2=3000 ≥ 2560 ✓；3000/2=1500 ✗
        assertEquals(4, ImageShrink.sampleSize(12000, 9000))   // 12000→6000→3000 ≥ 2560 ✓
        assertTrue(maxOf(12000, 9000) / ImageShrink.sampleSize(12000, 9000) >= ImageShrink.MAX_EDGE)
    }

    @Test
    fun `长边看的是较大的那条边`() {
        assertEquals(ImageShrink.sampleSize(9000, 100), ImageShrink.sampleSize(100, 9000))
    }

    @Test
    fun `尺寸和体积都达标就不重编码`() {
        assertFalse(ImageShrink.needsShrink(1_000_000, 2000, 1500))
        assertTrue(ImageShrink.needsShrink(25L * 1024 * 1024, 2000, 1500))   // 体积超
        assertTrue(ImageShrink.needsShrink(1_000_000, 4000, 3000))            // 尺寸超
    }

    @Test
    fun `非法尺寸直接拒绝`() {
        try {
            ImageShrink.sampleSize(0, 100)
            throw AssertionError("应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
