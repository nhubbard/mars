package edu.missouristate.mars.api

import edu.missouristate.mars.api.InstructionSourceMap.Location

/** An assembled program with instructions and the source metadata for its original image. */
interface Program {
    fun instructionCount(): Int

    fun sourceMap(): InstructionSourceMap = InstructionSourceMap.EMPTY

    /** Return instructions emitted at a physical line or through a macro call at that line. */
    fun instructionsAt(location: Location, checkCancellation: Runnable): List<AssembledInstruction> = emptyList()

    /** Return basic instructions emitted by pseudo-instructions. */
    fun pseudoInstructions(checkCancellation: Runnable): List<AssembledInstruction> = emptyList()

    fun symbolMap(): AssemblySymbolMap = AssemblySymbolMap.EMPTY
}
