package edu.missouristate.mars.core

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.mips.hardware.MemoryState.DataImage
import java.util.List.copyOf

/** Detached instructions, initialized memory, and source metadata from a successful assembly. */
class AssemblyImage internal constructor(
    @JvmField internal val filename: String?,
    @JvmField internal val layout: String,
    @JvmField internal val entry: Int,
    instructions: List<ProgramStatement.Image>,
    @JvmField internal val data: DataImage,
    @JvmField internal val sources: InstructionSourceMap,
    @JvmField internal val symbols: AssemblySymbolMap,
) {
    @JvmField internal val instructions: List<ProgramStatement.Image> = copyOf(instructions)

    /** Compare executable output independently of source names and positions. */
    fun hasSameMachineImage(other: AssemblyImage, checkCancellation: Runnable): Boolean {
        if (entry != other.entry || layout != other.layout || instructions.size != other.instructions.size) return false
        for (index in instructions.indices) {
            checkCancellation.run()
            if (!instructions[index].hasSameMachineWord(other.instructions[index])) return false
        }
        return data.hasSameContents(other.data, checkCancellation)
    }
}
