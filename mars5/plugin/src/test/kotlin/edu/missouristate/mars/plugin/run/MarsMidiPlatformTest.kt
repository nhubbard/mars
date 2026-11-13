package edu.missouristate.mars.plugin.run

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.plugin.MarsSessionService
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsMidiPlatformTest {
    private val projectFixture = projectFixture()

    @Test
    fun `a completed synchronous MIDI tone returns to the program`() = timeoutRunBlocking {
        val execution = Job()
        val calls = mutableListOf<Tone>()
        try {
            MarsSessionService.createMarsComponent().context().use { session ->
                val fake = object : SyscallEnvironment by session.syscallEnvironment {
                    override fun playTone(pitch: Int, duration: Int, instrument: Int, volume: Int, synchronous: Boolean) {
                        calls += Tone(pitch, duration, instrument, volume, synchronous)
                    }
                }
                val dialogs = MarsSyscallDialogs(projectFixture.get(), "audio.asm", execution)
                val environment = MarsIdeSyscallEnvironment(execution, fake, dialogs) {}
                environment.playTone(72, 50, 4, 100, true)
                assertEquals(listOf(Tone(72, 50, 4, 100, true)), calls)
                assertTrue(execution.isActive)
            }
        } finally {
            execution.cancel()
        }
    }

    @Test
    fun `MIDI forwards asynchronous tones and interrupts a synchronous tone with its launch`() = timeoutRunBlocking<Unit> {
        val execution = Job()
        val calls = Channel<Tone>(Channel.UNLIMITED)
        val release = CountDownLatch(1)
        val interrupted = CompletableDeferred<Unit>()
        try {
            MarsSessionService.createMarsComponent().context().use { session ->
                val fake = object : SyscallEnvironment by session.syscallEnvironment {
                    override fun playTone(pitch: Int, duration: Int, instrument: Int, volume: Int, synchronous: Boolean) {
                        calls.trySend(Tone(pitch, duration, instrument, volume, synchronous))
                        if (synchronous) {
                            try {
                                release.await()
                            } catch (_: InterruptedException) {
                                interrupted.complete(Unit)
                            }
                        }
                    }
                }
                val dialogs = MarsSyscallDialogs(projectFixture.get(), "audio.asm", execution)
                val environment = MarsIdeSyscallEnvironment(execution, fake, dialogs) {}
                environment.playTone(60, 200, 2, 90, false)
                assertEquals(Tone(60, 200, 2, 90, false), calls.receive())
                val waiting = async(Dispatchers.Default) {
                    runCatching { environment.playTone(65, 60_000, 3, 80, true) }
                }
                assertEquals(Tone(65, 60_000, 3, 80, true), calls.receive())
                assertFalse(waiting.isCompleted)
                execution.cancel()
                interrupted.await()
                assertInstanceOf(CancellationException::class.java, waiting.await().exceptionOrNull())
            }
        } finally {
            execution.cancel()
            release.countDown()
        }
    }

    private data class Tone(val pitch: Int, val duration: Int, val instrument: Int, val volume: Int, val synchronous: Boolean)
}
