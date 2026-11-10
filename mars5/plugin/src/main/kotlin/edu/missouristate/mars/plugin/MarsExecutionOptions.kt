package edu.missouristate.mars.plugin

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
) {
    fun applyForAssembly(session: MarsContext) {
        applyTo(session.settings)
        if (session.settings.memoryConfiguration != memoryLayout.identifier) {
            session.setMemoryConfiguration(memoryLayout.identifier)
        }
    }

    fun applyTo(settings: SimulationSettings) {
        settings.setDelayedBranchingEnabled(delayedBranching)
        settings.setSelfModifyingCodeEnabled(selfModifyingCode)
        settings.setExtendedAssemblerEnabled(allowPseudoInstructions)
        settings.setWarningsAreErrors(warningsAreErrors)
        settings.setBareMachineEnabled(bareMachine)
    }
}
