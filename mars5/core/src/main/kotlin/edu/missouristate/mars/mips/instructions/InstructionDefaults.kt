package edu.missouristate.mars.mips.instructions

/** The shared instruction catalog used for reference and disassembly. */
object InstructionDefaults {
    private object Holder {
        val instructions = InstructionSet().apply { populate() }
    }

    @JvmStatic
    fun get(): InstructionSet = Holder.instructions
}
