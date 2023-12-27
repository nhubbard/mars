/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
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

import java.util.concurrent.Executors

/**
 * Creates a Tone object and passes it to a thread to "play" it via MIDI.
 */
class ToneGenerator {
    companion object {
        /** The default pitch value for the tone. The default value of 60 is middle C. */
        const val DEFAULT_PITCH: Byte = 60

        /** The default duration of the tone; defaults to 1000 milliseconds. */
        const val DEFAULT_DURATION = 1000

        /** The default instrument of the tone; defaults to 0 to play as a piano. */
        const val DEFAULT_INSTRUMENT: Byte = 0

        /** The default volume of the tone. The default value is 100 out of 127 (approx. 80% volume). */
        const val DEFAULT_VOLUME: Byte = 100

        @JvmStatic private val threadPool = Executors.newCachedThreadPool()
    }

    /**
     * Produces a Tone with the specified pitch, duration, and instrument at the specified volume.
     * This variant runs asynchronously from a thread pool.
     *
     * @param pitch The desired pitch in semitones (0 to 127). For example, a value of 60 is middle C.
     * @param duration The desired duration in milliseconds.
     * @param instrument The desired instrument/patch number from 0 to 127. See the
     * [General MIDI instrument patch map](http://www.midi.org/about-midi/gm/gm1sound.shtml#instrument)
     * for more instruments associated with each value.
     * @param volume The desired volume for the initial "attack" (MIDI velocity) represented by a positive byte value.
     */
    fun generateTone(pitch: Byte, duration: Int, instrument: Byte, volume: Byte) {
        threadPool.execute(Tone(pitch, duration, instrument, volume))
    }

    /**
     * Produces a Tone with the specified pitch, duration, and instrument at the specified volume.
     * This variant runs synchronously, blocking the current thread until playback is complete.
     *
     * @param pitch The desired pitch in semitones (0 to 127). For example, a value of 60 is middle C.
     * @param duration The desired duration in milliseconds.
     * @param instrument The desired instrument/patch number from 0 to 127. See the
     * [General MIDI instrument patch map](http://www.midi.org/about-midi/gm/gm1sound.shtml#instrument)
     * for more instruments associated with each value.
     * @param volume The desired volume for the initial "attack" (MIDI velocity) represented by a positive byte value.
     */
    fun generateToneSynchronously(pitch: Byte, duration: Int, instrument: Byte, volume: Byte) {
        Tone(pitch, duration, instrument, volume).run()
    }
}