package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.api.MarsTarget

/** The shared instruction catalog used for reference and disassembly. */
object InstructionDefaults {
    private object Holder {
        val legacy = InstructionSet().apply { populate(MarsTarget.MARS32) }
    }

    @JvmStatic
    fun get(): InstructionSet = forTarget(MarsTarget.DEFAULT)

    private val wide by lazy { InstructionSet().apply { populate(MarsTarget.MARS64) } }
    private val editor by lazy { InstructionSet().apply { populate(MarsTarget.MARS64, includeLegacySourceForms = true) } }

    fun forTarget(target: MarsTarget): InstructionSet = if (target == MarsTarget.MARS64) wide else Holder.legacy

    /** The editor recognizes the union of both targets before project-specific analysis. */
    fun editorCatalog(): InstructionSet = editor
}
