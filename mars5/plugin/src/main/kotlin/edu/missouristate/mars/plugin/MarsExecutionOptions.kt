package edu.missouristate.mars.plugin

import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.core.SimulationSettings

internal enum class MarsMemoryLayout(val identifier: String, val messageKey: String) {
  DEFAULT("Default", "build.settings.memory.default"),
  DATA_AT_ZERO("CompactDataAtZero", "build.settings.memory.data"),
  TEXT_AT_ZERO("CompactTextAtZero", "build.settings.memory.text");

  companion object {
    fun fromIdentifier(identifier: String): MarsMemoryLayout =
        entries.firstOrNull { it.identifier == identifier } ?: DEFAULT
  }
}

internal data class MarsExecutionOptions(
    val delayedBranching: Boolean = false,
    val selfModifyingCode: Boolean = false,
    val allowPseudoInstructions: Boolean = true,
    val warningsAreErrors: Boolean = false,
    val memoryLayout: MarsMemoryLayout = MarsMemoryLayout.DEFAULT,
    val bareMachine: Boolean = false,
    val utf8Text: Boolean = false,
    val target: MarsTarget = MarsTarget.DEFAULT,
    val assemblerErrorLimit: Int = ApplicationInfo.maximumErrorMessages,
) {
  fun applyForAssembly(session: MarsContext) {
    session.selectTarget(target)
    applyTo(session.settings)
    if (session.settings.memoryConfiguration != memoryLayout.identifier) {
      session.setMemoryConfiguration(memoryLayout.identifier)
    }
  }

  fun applyTo(settings: SimulationSettings) {
    settings.isUtf8TextEnabled = utf8Text
    settings.isDelayedBranchingEnabled = delayedBranching
    settings.isSelfModifyingCodeEnabled = selfModifyingCode
    settings.isExtendedAssemblerEnabled = allowPseudoInstructions
    settings.isWarningsAreErrors = warningsAreErrors
    settings.isBareMachineEnabled = bareMachine
    settings.assemblerErrorLimit = assemblerErrorLimit
  }
}
