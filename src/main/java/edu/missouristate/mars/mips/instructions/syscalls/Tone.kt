/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package edu.missouristate.mars.mips.instructions.syscalls

import java.util.concurrent.locks.ReentrantLock
import javax.sound.midi.*
import kotlin.concurrent.withLock

/**
 * Contains important variables for a MIDI tone: pitch, duration, instrument/patch, and volume.
 * The tone can be passed to a thread executor or run synchronously and will be played using MIDI.
 *
 * @param pitch The desired pitch in semitones (0 to 127). For example, a value of 60 is middle C.
 * @param duration The desired duration in milliseconds.
 * @param instrument The desired instrument/patch number from 0 to 127. See the
 * [General MIDI instrument patch map](http://www.midi.org/about-midi/gm/gm1sound.shtml#instrument)
 * for more instruments associated with each value.
 * @param volume The desired volume for the initial "attack" (MIDI velocity) represented by a positive byte value.
 */
data class Tone(
    val pitch: Byte,
    val duration: Int,
    val instrument: Byte,
    val volume: Byte
) : Runnable {
    companion object {
        /** The tempo of the tone in milliseconds. Defaults to 1000 beats per second. */
        const val TEMPO = 1000f

        /** The default MIDI channel of the tone is 0, which maps to MIDI channel 1. */
        const val DEFAULT_CHANNEL = 0

        /**
         * The lock used to play the sound. Prevents a race condition that probably has since been fixed.
         */
        @JvmStatic private val openLock = ReentrantLock()
    }

    override fun run() {
        playTone()
    }

    private fun playTone() {
        try {
            val player: Sequencer
            openLock.withLock {
                player = MidiSystem.getSequencer()
                player.open()
            }

            // Create sequence
            val seq = Sequence(Sequence.PPQ, 1)
            player.tempoInMPQ = TEMPO
            val t = seq.createTrack()

            // Select instrument
            val inst = ShortMessage()
            inst.setMessage(ShortMessage.PROGRAM_CHANGE, DEFAULT_CHANNEL, instrument.toInt(), 0)
            val instChange = MidiEvent(inst, 0)
            t.add(instChange)

            val on = ShortMessage()
            on.setMessage(ShortMessage.NOTE_ON, DEFAULT_CHANNEL, pitch.toInt(), volume.toInt())
            val noteOn = MidiEvent(on, 0)
            t.add(noteOn)

            val off = ShortMessage()
            off.setMessage(ShortMessage.NOTE_OFF, DEFAULT_CHANNEL, pitch.toInt(), volume.toInt())
            val noteOff = MidiEvent(off, duration.toLong())
            t.add(noteOff)

            player.sequence = seq

            // Watches for the end of track event to pass control back to MARS
            val eot = EndOfTrackListener()
            player.addMetaEventListener(eot)

            player.start()

            try {
                eot.awaitEndOfTrack()
            } catch (ignored: InterruptedException) {
                // We don't care if it's interrupted.
            } finally {
                player.close()
            }
        } catch (e: MidiUnavailableException) {
            e.printStackTrace()
        } catch (e: InvalidMidiDataException) {
            e.printStackTrace()
        }
    }
}