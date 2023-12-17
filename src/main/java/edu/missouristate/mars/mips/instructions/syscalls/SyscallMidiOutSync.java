package edu.missouristate.mars.mips.instructions.syscalls;

import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.*;

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
	
