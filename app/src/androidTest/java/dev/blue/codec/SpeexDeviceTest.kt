package dev.blue.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Runs on a phone: checks the native codec produces the reference frame format. */
@RunWith(AndroidJUnit4::class)
class SpeexDeviceTest {

    private fun tone(n: Int) = ShortArray(n) { (8000 * sin(2 * PI * 440 * it / 8000.0)).toInt().toShort() }

    @Test
    fun quality1EncodesA20msFrameTo10Bytes() = Speex().use { s ->
        assertEquals(160, s.frameSize)
        assertEquals(Speex.FRAME_BYTES, s.encode(tone(160)).size)
    }

    @Test
    fun decodedToneKeepsItsEnergy() = Speex().use { s ->
        var out: ShortArray? = null
        repeat(10) { out = s.decode(s.encode(tone(160))) } // let the codec settle
        assertNotNull(out)
        assertTrue("decoded audio is silent", out!!.maxOf { abs(it.toInt()) } > 1000)
    }
}
