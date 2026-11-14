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
    private val editor by lazy { InstructionSet().apply { populate(MarsTarget.MARS64, includeLegacySourceForms = true) } }

    fun forTarget(target: MarsTarget): InstructionSet = if (target == MarsTarget.MARS64) wide else get()

    /** The editor recognizes the union of both targets before project-specific analysis. */
    fun editorCatalog(): InstructionSet = editor
}
