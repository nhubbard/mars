package edu.missouristate.mars.api

/** Headless-safe settings that affect assembly or simulation. */
interface MarsSettings {
    /** An empty identifier selects the default memory layout. */
    val memoryConfiguration: String
    val isDelayedBranchingEnabled: Boolean
    val isSelfModifyingCodeEnabled: Boolean
    val isWarningsAreErrors: Boolean
    val assemblerErrorLimit: Int
    val isExtendedAssemblerEnabled: Boolean
}
