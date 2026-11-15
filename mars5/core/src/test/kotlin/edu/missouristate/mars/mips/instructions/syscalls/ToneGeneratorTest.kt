package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.mips.instructions.syscalls.ToneGenerator.SequencerSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.sound.midi.InvalidMidiDataException
import javax.sound.midi.MetaEventListener
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Sequence
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
internal class ToneGeneratorTest {
    @Test
    fun synchronousToneWaitsForTheTrackAndClosesTheSequencer() {
        val device = RecordingSequencer()
        val failures = ArrayList<Exception?>()
        // The worker may be waiting for a MIDI callback, so cleanup must interrupt it.
        val executor = Executors.newSingleThreadExecutor()
        try {
            ToneGenerator(
                    SequencerSource { device.proxy() },
                    Consumer { e: Exception? -> failures.add(e) },
                )
                .use { generator ->
                    val tone =
                        executor.submit(
                            Runnable {
                                generator.generateToneSynchronously(
                                    65.toByte(),
                                    120,
                                    8.toByte(),
                                    90.toByte(),
                                )
                            },
                        )
                    Assertions.assertThat(device.started.await(3, TimeUnit.SECONDS)).isTrue()
                    Assertions.assertThat(tone).isNotDone()
                    Assertions.assertThat(device.tempo).isEqualTo(1000f)
                    val track = device.sequence!!.getTracks()[0]
                    assertEvent(track.get(0), 0, ShortMessage.PROGRAM_CHANGE, 8, 0)
                    assertEvent(track.get(1), 0, ShortMessage.NOTE_ON, 65, 90)
                    assertEvent(track.get(2), 120, ShortMessage.NOTE_OFF, 65, 90)
                    device.listener!!.meta(MetaMessage(47, ByteArray(0), 0))
                    tone.get(3, TimeUnit.SECONDS)
                    Assertions.assertThat(device.closed.getCount()).isZero()
                    Assertions.assertThat<Exception?>(failures).isEmpty()
                }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aCompletedSynchronousToneLeavesTheLaunchInterruptSet() {
        val device = RecordingSequencer()
        device.completeAndInterruptOnStart = true
        val failures = ArrayList<Exception?>()
        try {
            ToneGenerator(
                    SequencerSource { device.proxy() },
                    Consumer { e: Exception? -> failures.add(e) },
                )
                .use { generator ->
                    generator.generateToneSynchronously(60.toByte(), 200, 0.toByte(), 80.toByte())
                    Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue()
                    Assertions.assertThat(device.closed.getCount()).isZero()
                    Assertions.assertThat<Exception?>(failures).isEmpty()
                }
        } finally {
            // Clear the interrupt before JUnit reuses the test thread.
            Thread.interrupted()
        }
    }

    @Test
    fun asynchronousToneReturnsWhilePlayingAndSessionCloseInterruptsIt() {
        val device = RecordingSequencer()
        val failures = ArrayList<Exception?>()
        ToneGenerator(
                SequencerSource { device.proxy() },
                Consumer { e: Exception? -> failures.add(e) },
            )
            .use { generator ->
                generator.generateTone(60.toByte(), 60000, 0.toByte(), 80.toByte())
                Assertions.assertThat(device.started.await(3, TimeUnit.SECONDS)).isTrue()
                Assertions.assertThat(device.closed.getCount()).isEqualTo(1)
            }
        Assertions.assertThat(device.closed.await(3, TimeUnit.SECONDS)).isTrue()
        Assertions.assertThat<Exception?>(failures).isEmpty()
    }

    @Test
    fun reportsMissingDevicesAndClosesPlayersThatFailDuringInitialization() {
        val unavailable = MidiUnavailableException("Device unavailable")
        val failures = ArrayList<Exception?>()
        ToneGenerator(
                SequencerSource { throw unavailable },
                Consumer { e: Exception? -> failures.add(e) },
            )
            .use { generator ->
                generator.generateToneSynchronously(60.toByte(), 200, 0.toByte(), 80.toByte())
            }
        val badOpen = RecordingSequencer()
        badOpen.openFailure = MidiUnavailableException("Device busy")
        ToneGenerator(
                SequencerSource { badOpen.proxy() },
                Consumer { e: Exception? -> failures.add(e) },
            )
            .use { generator ->
                generator.generateToneSynchronously(60.toByte(), 200, 0.toByte(), 80.toByte())
            }
        val badSequence = RecordingSequencer()
        badSequence.sequenceFailure = InvalidMidiDataException("Cannot configure sequence")
        ToneGenerator(
                SequencerSource { badSequence.proxy() },
                Consumer { e: Exception? -> failures.add(e) },
            )
            .use { generator ->
                generator.generateToneSynchronously(60.toByte(), 200, 0.toByte(), 80.toByte())
            }
        Assertions.assertThat(badOpen.closed.getCount()).isZero()
        Assertions.assertThat(badSequence.closed.getCount()).isZero()
        Assertions.assertThat<Exception?>(failures)
            .containsExactly(unavailable, badOpen.openFailure, badSequence.sequenceFailure)
    }

    private class RecordingSequencer {
        val started: CountDownLatch = CountDownLatch(1)
        val closed: CountDownLatch = CountDownLatch(1)
        var sequence: Sequence? = null
        var tempo: Float = 0f
        var listener: MetaEventListener? = null
        var openFailure: MidiUnavailableException? = null
        var sequenceFailure: InvalidMidiDataException? = null
        var completeAndInterruptOnStart: Boolean = false

        fun proxy(): Sequencer {
            return Proxy.newProxyInstance(
                Sequencer::class.java.getClassLoader(),
                arrayOf<Class<*>>(Sequencer::class.java),
                InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                    when (method!!.getName()) {
                        "open" -> {
                            openFailure?.let { throw it }
                        }

                        "setTempoInMPQ" -> tempo = requireNotNull(args)[0] as Float
                        "setSequence" -> {
                            sequenceFailure?.let { throw it }
                            sequence = args!![0] as Sequence
                        }

                        "addMetaEventListener" -> {
                            listener = args!![0] as MetaEventListener
                            return@InvocationHandler true
                        }

                        "start" -> {
                            started.countDown()
                            if (completeAndInterruptOnStart) {
                                listener!!.meta(MetaMessage(47, ByteArray(0), 0))
                                Thread.currentThread().interrupt()
                            }
                        }

                        "close" -> closed.countDown()
                        else -> throw UnsupportedOperationException(method.getName())
                    }
                    null
                },
            ) as Sequencer
        }
    }

    companion object {
        private fun assertEvent(
            event: MidiEvent,
            tick: Long,
            command: Int,
            first: Int,
            second: Int,
        ) {
            val message = event.getMessage() as ShortMessage
            Assertions.assertThat(event.getTick()).isEqualTo(tick)
            Assertions.assertThat(message.getCommand()).isEqualTo(command)
            Assertions.assertThat(message.getChannel()).isZero()
            Assertions.assertThat(message.getData1()).isEqualTo(first)
            Assertions.assertThat(message.getData2()).isEqualTo(second)
        }
    }
}
