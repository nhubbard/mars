package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.api.MarsTarget

/** The shared instruction catalog used for reference and disassembly. */
object InstructionDefaults {
    private object Holder {
        val instructions = InstructionSet().apply { populate() }
    }

    @JvmStatic
    fun get(): InstructionSet = Holder.instructions

    private val wide by lazy { InstructionSet().apply { populate(MarsTarget.MARS64) } }

    /** The editor recognizes the union of both targets before project-specific analysis. */
    fun editorCatalog(): InstructionSet = wide
}
