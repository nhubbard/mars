/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.RegisterFile;

/**
 * Service to output simulated MIDI tone to sound card.  The call does
 * not return until the tone duration has elapsed.  By contrast, syscall 31
 * (MidiOut) returns immediately upon generating the tone.
 */

public class SyscallMidiOutSync extends AbstractSyscall {

    // Endpoints of ranges for the three "byte" parameters.  The duration
    // parameter is limited at the high end only by the int range.
    static final int rangeLowEnd = 0;
    static final int rangeHighEnd = 127;

    /**
     * Build an instance of the MIDI (simulated) out syscall.  Default service number
     * is 33 and name is "MidiOutSync".
     */
    public SyscallMidiOutSync() {
        super(33, "MidiOutSync");
    }

    /**
     * Performs syscall function to send MIDI output to sound card.  The syscall does not
     * return until after the duration period ($a1) has elapsed.  This requires
     * four arguments in registers $a0 through $a3.<br>
     * $a0 - pitch (note).  Integer value from 0 to 127, with 60 being middle-C on a piano.<br>
     * $a1 - duration. Integer value in milliseconds.<br>
     * $a2 - instrument.  Integer value from 0 to 127, with 0 being acoustic grand piano.<br>
     * $a3 - volume.  Integer value from 0 to 127.<br>
     * Default values, in case any parameters are outside the above ranges, are $a0=60, $a1=1000,
     * $a2=0, $a3=100.<br>
     * See MARS documentation elsewhere or www.midi.org for more information.  Note that the pitch,
     * instrument and volume value ranges 0-127 are from javax.sound.midi; actual MIDI instruments
     * use the range 1-128.
     */
    public void simulate(ProgramStatement statement) throws ProcessingException {
        int pitch = RegisterFile.getValue(4); // $a0
        int duration = RegisterFile.getValue(5); // $a1
        int instrument = RegisterFile.getValue(6); // $a2
        int volume = RegisterFile.getValue(7); // $a3
        if (pitch < rangeLowEnd || pitch > rangeHighEnd) pitch = ToneGenerator.DEFAULT_PITCH;
        if (duration < 0) duration = ToneGenerator.DEFAULT_DURATION;
        if (instrument < rangeLowEnd || instrument > rangeHighEnd) instrument = ToneGenerator.DEFAULT_INSTRUMENT;
        if (volume < rangeLowEnd || volume > rangeHighEnd) volume = ToneGenerator.DEFAULT_VOLUME;
        new ToneGenerator().generateToneSynchronously((byte) pitch, duration, (byte) instrument, (byte) volume);
    }

}
	
