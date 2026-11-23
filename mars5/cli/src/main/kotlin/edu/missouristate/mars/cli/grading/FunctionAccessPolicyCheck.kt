package edu.missouristate.mars.cli.grading

/** A bounded label-relative permitted window; zero length permits no access. */
data class FunctionDataWindow(val at: FunctionDataAddress, val byteLength: Int) {
    init {
        require(byteLength in 0..ConsoleMemoryCheck.MAX_BYTES) { "Permit zero through one MiB of function data" }
    }
}

/**
 * Every execution data notice overlapping [from, until) must fit wholly within one
 * permitted window of its access type. This includes unnamed padding in an assembled
 * caller layout. Windows may lie outside the watched span when caller objects are reordered.
 * Traffic outside the watched span is unaffected, including a callee's private stack.
 */
data class FunctionAccessPolicyCheck(
    val from: FunctionDataAddress,
    val until: FunctionDataAddress,
    val readable: List<FunctionDataWindow> = emptyList(),
    val writable: List<FunctionDataWindow> = emptyList(),
    val maxAccesses: Int = 65_536,
    val checkReads: Boolean = true,
) {
    init {
        require(maxAccesses in 1..1_000_000) { "Observe one through one million memory accesses per policy" }
        require(checkReads || readable.isEmpty()) { "A store-only policy must not declare ignored read windows" }
        require(readable.size + writable.size <= 64 &&
            readable.sumOf { it.byteLength.toLong() } + writable.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
            "Access policy permits at most 64 windows or one MiB of data"
        }
    }
}

internal fun functionAccessPolicies(policies: List<FunctionAccessPolicyCheck>, history: FunctionAccessHistoryCheck? = null) {
    require(policies.sumOf { it.maxAccesses.toLong() } + (history?.maxAccesses ?: 0) <= 64L * 65_536) {
        "Access policies exceed the combined 4194304-notice history allowance"
    }
    require(policies.size <= 64 && policies.sumOf { policy ->
        policy.readable.sumOf { it.byteLength.toLong() } + policy.writable.sumOf { it.byteLength.toLong() }
    } <= ConsoleMemoryCheck.MAX_BYTES) { "Access policies exceed 64 watched intervals or one MiB of permitted data" }
}
