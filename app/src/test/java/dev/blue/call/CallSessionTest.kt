package dev.blue.call

import dev.blue.protocol.Crypto
import dev.blue.protocol.Frame
import dev.blue.protocol.Messages
import dev.blue.protocol.Opcode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {

    private class Recorder : CallSession.Events {
        var code: String? = null
        var incoming: String? = null
        var answered = false
        var rejected = false
        var ended = false
        val audio = mutableListOf<ByteArray>()
        override fun onSecured(checkCode: String) { code = checkCode }
        override fun onIncomingCall(number: String) { incoming = number }
        override fun onAnswered() { answered = true }
        override fun onRejected() { rejected = true }
        override fun onEnded() { ended = true }
        override fun onAudio(frames: ByteArray) { audio += frames }
    }

    private val callerEv = Recorder()
    private val calleeEv = Recorder()
    private val wire = mutableListOf<ByteArray>() // frames sent by the caller, for inspection
    private lateinit var caller: CallSession
    private lateinit var callee: CallSession

    private fun connect(mode: Crypto.Mode = Crypto.Mode.SECURE) {
        caller = CallSession(CallSession.Role.CALLER, mode, "09171234567", { f, _ -> wire += f; callee.receive(f) }, callerEv)
        callee = CallSession(CallSession.Role.CALLEE, mode, "09998887777", { f, _ -> caller.receive(f) }, calleeEv)
        caller.start()
    }

    @Test
    fun bothSidesSecureWithTheSameCheckCode() {
        connect()
        assertTrue(callerEv.code != null)
        assertEquals(callerEv.code, calleeEv.code)
    }

    @Test
    fun calleeRingsWithTheCallersNumber() {
        connect()
        assertEquals("09171234567", calleeEv.incoming)
        assertEquals(CallSession.State.RINGING, callee.state)
    }

    @Test
    fun answerMakesBothSidesActive() {
        connect()
        callee.answer()
        assertTrue(callerEv.answered)
        assertEquals(CallSession.State.ACTIVE, caller.state)
        assertEquals(CallSession.State.ACTIVE, callee.state)
    }

    @Test
    fun audioFlowsOnlyWhileActive() {
        connect()
        caller.sendAudio(byteArrayOf(1, 2, 3))
        assertTrue(calleeEv.audio.isEmpty())
        callee.answer()
        caller.sendAudio(byteArrayOf(1, 2, 3))
        callee.sendAudio(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(1, 2, 3), calleeEv.audio.single())
        assertArrayEquals(byteArrayOf(4), callerEv.audio.single())
    }

    @Test
    fun secureModeEncryptsAudio() {
        connect()
        callee.answer()
        caller.sendAudio(byteArrayOf(1, 2, 3))
        assertFalse(wire.last().copyOfRange(Frame.HEADER, wire.last().size).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun xiaomiModeSendsAudioInTheClear() {
        connect(Crypto.Mode.XIAOMI)
        callee.answer()
        caller.sendAudio(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), wire.last().copyOfRange(Frame.HEADER, wire.last().size))
    }

    @Test
    fun rejectEndsTheCallerSide() {
        connect()
        callee.reject()
        assertTrue(callerEv.rejected)
        assertEquals(CallSession.State.ENDED, caller.state)
    }

    @Test
    fun hangUpEndsTheOtherSide() {
        connect()
        callee.answer()
        caller.hangUp()
        assertTrue(calleeEv.ended)
        assertEquals(CallSession.State.ENDED, callee.state)
    }

    @Test
    fun callRequestBeforeKeyExchangeIsIgnored() {
        val ev = Recorder()
        val s = CallSession(CallSession.Role.CALLEE, Crypto.Mode.SECURE, "1", { _, _ -> }, ev)
        s.receive(Frame.encode(Frame.TYPE_NOTIFY, 1, Opcode.CALL_REQUEST, 0, Messages.callRequest("666", "")))
        assertNull(ev.incoming)
    }

    @Test
    fun xiaomiModeQueriesTheBlockListBetweenCapabilitiesAndKey() {
        connect(Crypto.Mode.XIAOMI)
        val packets = wire.map { Frame.parse(it)!! }
        val opcodes = packets.map { it.opcode }
        val block = opcodes.indexOf(Opcode.QUERY_BLACK_LIST)
        assertTrue(opcodes.indexOf(Opcode.QUERY_CAPACITY_LIST) < block && block < opcodes.indexOf(Opcode.QUERY_AES_KEY))
        assertEquals("09171234567:", String(packets[block].payload))
        assertEquals("09171234567", calleeEv.incoming) // the call still goes through
    }

    @Test
    fun secureModeSkipsTheBlockListQuery() {
        connect()
        assertFalse(wire.any { Frame.parse(it)!!.opcode == Opcode.QUERY_BLACK_LIST })
    }

    @Test
    fun callerQueriesCapabilitiesBeforeRequestingTheKey() {
        connect()
        val opcodes = wire.map { Frame.parse(it)!!.opcode }
        assertTrue(opcodes.indexOf(Opcode.QUERY_CAPACITY_LIST) < opcodes.indexOf(Opcode.QUERY_AES_KEY))
    }
}
