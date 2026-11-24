package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.api.MarsTarget

/** Immutable instruction definitions and decoding tables, shared by sessions and IDE readers. */
object InstructionDefaults {
  private val legacy by lazy { buildCatalog(MarsTarget.MARS32) }
  private val wide by lazy { buildCatalog(MarsTarget.MARS64) }
  private val editor by lazy { wide.withLegacySourceForms(legacy) }

  @JvmStatic fun get(): InstructionSet = forTarget(MarsTarget.DEFAULT)

  fun forTarget(target: MarsTarget): InstructionSet =
      when (target) {
        MarsTarget.MARS32 -> legacy
        MarsTarget.MARS64 -> wide
      }

  /** The editor recognizes the union of both targets before project-specific analysis. */
  fun editorCatalog(): InstructionSet = editor

  private fun buildCatalog(target: MarsTarget): InstructionSet =
      InstructionSet().apply {
        populate(target)
        freeze()
      }
}
