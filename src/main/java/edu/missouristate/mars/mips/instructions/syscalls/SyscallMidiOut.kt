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

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.mips.hardware.RegisterFile.getValue

/**
 * Service to output simulated MIDI tone to sound card.  The call returns
 * immediately upon generating the tone.  By contrast, syscall 33
 * (MidiOutSync) does not return until tone duration has elapsed.
 */
class SyscallMidiOut : AbstractSyscall(31, "MidiOut") {
    /**
     * Performs syscall function to send MIDI output to sound card.  This requires
     * four arguments in registers $a0 through $a3.
     *
     * - $a0 - pitch (note).  Integer value from 0 to 127, with 60 being middle-C on a piano.
     * - $a1 - duration. Integer value in milliseconds.
     * - $a2 - instrument.  Integer value from 0 to 127, with 0 being acoustic grand piano.
     * - $a3 - volume.  Integer value from 0 to 127.
     *
     * Default values, in case any parameters are outside the above ranges, are $a0=60, $a1=1000,
     * $a2=0, $a3=100.
     *
     * See MARS documentation elsewhere or www.midi.org for more information.  Note that the pitch,
     * instrument and volume value ranges 0-127 are from javax.sound.midi; actual MIDI instruments
     * use the range 1-128.
     */
    @Throws(ProcessingException::class)
    override fun simulate(statement: ProgramStatement) {
        var pitch = getValue(4) // $a0
        var duration = getValue(5) // $a1
        var instrument = getValue(6) // $a2
        var volume = getValue(7) // $a3
        if (pitch < rangeLowEnd || pitch > rangeHighEnd) pitch = ToneGenerator.DEFAULT_PITCH.toInt()
        if (duration < 0) duration = ToneGenerator.DEFAULT_DURATION
        if (instrument < rangeLowEnd || instrument > rangeHighEnd) instrument = ToneGenerator.DEFAULT_INSTRUMENT.toInt()
        if (volume < rangeLowEnd || volume > rangeHighEnd) volume = ToneGenerator.DEFAULT_VOLUME.toInt()
        ToneGenerator().generateTone(pitch.toByte(), duration, instrument.toByte(), volume.toByte())
    }

    companion object {
        // Endpoints of ranges for the three "byte" parameters.  The duration
        // parameter is limited at the high end only by the int range.
        const val rangeLowEnd: Int = 0
        const val rangeHighEnd: Int = 127
    }
}