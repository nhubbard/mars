package edu.missouristate.mars.mips.instructions.syscalls

import edu.missouristate.mars.core.InstructionContext

internal fun InstructionContext.playMidiSyscall(synchronous: Boolean) {
    val pitch = registerFile.getValue(4)
    val duration = registerFile.getValue(5)
    val instrument = registerFile.getValue(6)
    val volume = registerFile.getValue(7)
    environment().playTone(
        if (pitch in 0..127) pitch else ToneGenerator.DEFAULT_PITCH.toInt(),
        if (duration >= 0) duration else ToneGenerator.DEFAULT_DURATION,
        if (instrument in 0..127) instrument else ToneGenerator.DEFAULT_INSTRUMENT.toInt(),
        if (volume in 0..127) volume else ToneGenerator.DEFAULT_VOLUME.toInt(),
        synchronous,
    )
}
