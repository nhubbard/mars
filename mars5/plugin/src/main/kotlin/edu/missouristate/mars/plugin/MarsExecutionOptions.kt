package edu.missouristate.mars.plugin

import edu.missouristate.mars.core.SimulationSettings

internal data class MarsExecutionOptions(
    val delayedBranching: Boolean = false,
    val selfModifyingCode: Boolean = false,
) {
    fun applyTo(settings: SimulationSettings) {
        settings.setDelayedBranchingEnabled(delayedBranching)
        settings.setSelfModifyingCodeEnabled(selfModifyingCode)
    }
}
