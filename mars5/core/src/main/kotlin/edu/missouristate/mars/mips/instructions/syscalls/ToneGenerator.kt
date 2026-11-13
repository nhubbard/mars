package edu.missouristate.mars.mips.instructions.syscalls

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import javax.sound.midi.InvalidMidiDataException
import javax.sound.midi.MetaEventListener
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Sequence
import javax.sound.midi.Sequencer
import javax.sound.midi.ShortMessage
import kotlin.concurrent.withLock

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////
//
//  The ToneGenerator and Tone classes were developed by Otterbein College
//  student Tony Brock in July 2007.  They simulate MIDI output through the
//  computers soundcard using classes and methods of the javax.sound.midi
//  package.
//
//  Max Hailperin <max@gustavus.edu> updated ToneGenerator on 2009-10-19.
//  The update added a reliable way to wait for synchronous tones.
//  It moved asynchronous tones to a thread pool and removed unused overloads.
/////////////////////////////////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////

/** Plays MIDI tones for one syscall environment. */
class ToneGenerator internal constructor(
    private val sequencers: SequencerSource,
    private val reportFailure: Consumer<Exception>,
) : AutoCloseable {
    internal fun interface SequencerSource {
        @Throws(MidiUnavailableException::class)
        fun create(): Sequencer
    }

    constructor(reportFailure: Consumer<Exception>) : this(SequencerSource(MidiSystem::getSequencer), reportFailure)

    private var threadPool: ExecutorService? = null

    @Synchronized
    fun generateTone(pitch: Byte, duration: Int, instrument: Byte, volume: Byte) {
        val pool = threadPool ?: Executors.newCachedThreadPool { task ->
            Thread(task, "mars-midi").apply { isDaemon = true }
        }.also { threadPool = it }
        pool.execute { generateToneSynchronously(pitch, duration, instrument, volume) }
    }

    @Synchronized
    override fun close() {
        threadPool?.shutdownNow()
        threadPool = null
    }

    fun generateToneSynchronously(pitch: Byte, duration: Int, instrument: Byte, volume: Byte) {
        try {
            openSequencer().use { player ->
                if (Thread.currentThread().isInterrupted) return
                val sequence = Sequence(Sequence.PPQ, 1)
                player.tempoInMPQ = TEMPO.toFloat()
                val track = sequence.createTrack()
                track.add(MidiEvent(ShortMessage(ShortMessage.PROGRAM_CHANGE, DEFAULT_CHANNEL, instrument.toInt(), 0), 0))
                track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, DEFAULT_CHANNEL, pitch.toInt(), volume.toInt()), 0))
                track.add(
                    MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, DEFAULT_CHANNEL, pitch.toInt(), volume.toInt()), duration.toLong()),
                )
                player.sequence = sequence
                val endOfTrack = EndOfTrackListener()
                player.addMetaEventListener(endOfTrack)
                player.start()
                endOfTrack.await()
            }
        } catch (_: InterruptedException) {
            // The launch stopped while opening the device or playing the tone.
        } catch (failure: MidiUnavailableException) {
            reportUnlessInterrupted(failure)
        } catch (failure: InvalidMidiDataException) {
            reportUnlessInterrupted(failure)
        } catch (failure: RuntimeException) {
            reportUnlessInterrupted(failure)
        }
    }

    private fun openSequencer(): Sequencer {
        // Serialize opening the MIDI device to avoid a race in native sequencer initialization.
        openLock.lockInterruptibly()
        try {
            val player = sequencers.create()
            try {
                player.open()
                return player
            } catch (failure: MidiUnavailableException) {
                player.close()
                throw failure
            } catch (failure: RuntimeException) {
                player.close()
                throw failure
            }
        } finally {
            openLock.unlock()
        }
    }

    private fun reportUnlessInterrupted(failure: Exception) {
        if (!Thread.currentThread().isInterrupted) reportFailure.accept(failure)
    }

    private class EndOfTrackListener : MetaEventListener {
        private val lock = ReentrantLock()
        private val completed = lock.newCondition()
        private var ended = false

        override fun meta(message: MetaMessage) {
            if (message.type == END_OF_TRACK) {
                lock.withLock {
                    ended = true
                    completed.signalAll()
                }
            }
        }

        fun await() {
            lock.withLock {
                while (!ended) completed.await()
            }
        }
    }

    companion object {
        const val DEFAULT_PITCH: Byte = 60
        const val DEFAULT_DURATION = 1000
        const val DEFAULT_INSTRUMENT: Byte = 0
        const val DEFAULT_VOLUME: Byte = 100

        private const val TEMPO = 1000
        private const val DEFAULT_CHANNEL = 0
        private const val END_OF_TRACK = 47
        private val openLock = ReentrantLock()
    }
}
