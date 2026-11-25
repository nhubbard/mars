package edu.missouristate.mars.cli.grading

import java.nio.ByteOrder
import java.util.List.copyOf
import java.util.Map.copyOf as copyMap

/**
 * Continuous execution between public markers; expectations contain data, never downloaded
 * predicates.
 */
data class ConsolePublicationCheck(
    val label: String,
    val initialMemory: List<FunctionMemoryCheck>,
    val snapshots: List<State>,
    val finalState: State,
) {
  /**
   * Policies cover the preceding interval; registers and memory describe the state before its
   * instruction. [label] optionally selects this snapshot's marker; the final state always observes
   * termination.
   */
  data class State(
      val memory: List<FunctionMemoryCheck> = emptyList(),
      val addressMemory: List<FunctionAddressMemoryCheck> = emptyList(),
      val accessPolicies: List<FunctionAccessPolicyCheck> = emptyList(),
      val label: String? = null,
      val expectedRegisters: Map<String, Long> = emptyMap(),
  ) {
    init {
      label?.let(::functionLabel)
      functionRegisters(expectedRegisters, writable = false)
      functionMemory(memory)
      require(memory.size + addressMemory.size <= ConsoleMemoryCheck.MAX_RANGES) {
        "Declare at most 64 publication results"
      }
      functionAccessPolicies(accessPolicies)
    }

    internal val byteLength: Long
      get() = memory.sumOf { it.byteLength.toLong() } + 8L * addressMemory.size

    internal fun detached() =
        copy(
            memory = copyOf(memory),
            addressMemory = copyOf(addressMemory),
            expectedRegisters = copyMap(expectedRegisters),
            accessPolicies =
                copyOf(
                    accessPolicies.map {
                      it.copy(readable = copyOf(it.readable), writable = copyOf(it.writable))
                    },
                ),
        )

    internal fun verify(execution: AssemblyCase) {
      for ((register, value) in expectedRegisters) execution.expectRegister(register, value)
      for (range in memory) execution.expectBytes(
          range.label,
          *range.decoded(),
          offset = range.offset,
      )
      for ((at, value) in addressMemory) {
        val address = execution.readDataAddress(value.label, value.offset.toLong())
        val bytes =
            IntArray(8) { index ->
              val shift =
                  8 * if (execution.byteOrder == ByteOrder.LITTLE_ENDIAN) index else 7 - index
              (address ushr shift and 255L).toInt()
            }
        execution.expectBytes(at.label, *bytes, offset = at.offset)
      }
    }
  }

  init {
    functionLabel(label)
    require(finalState.label == null) { "The final state observes termination, not another marker" }
    functionMemory(initialMemory)
    require(snapshots.size <= 256) { "Observe at most 256 public marker visits" }
    require(
        initialMemory.sumOf { it.byteLength.toLong() } +
            snapshots.sumOf { it.byteLength } +
            finalState.byteLength <= ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Combined publication setup/results exceed one MiB"
    }
  }

  internal fun detached() =
      copy(
          initialMemory = copyOf(initialMemory),
          snapshots = copyOf(snapshots.map { it.detached() }),
          finalState = finalState.detached(),
      )

  /** Assignment scripts share the same checker as native formative assessments. */
  fun verify(execution: AssemblyCase) = run(execution) {}

  internal fun run(execution: AssemblyCase, checkCancellation: () -> Unit) =
      with(execution) {
        for (range in initialMemory) setBytes(range.label, *range.decoded(), offset = range.offset)
        val boundaries = snapshots.map { it.label ?: label }
        val publications =
            boundaries.ifEmpty { listOf(label) }.distinct().associateWith { traceExecution(it) }
        for ((index, state) in snapshots.withIndex()) {
          checkCancellation()
          val boundary = boundaries[index]
          try {
            FunctionAccessPolicyObservation(this, state.accessPolicies).use { policies ->
              if (index > 0) step()
              runUntil(boundary)
              state.verify(this)
              policies.expectAllowed()
            }
          } catch (failure: CaseFailure) {
            throw CaseFailure("Publication ${index + 1} at $boundary: ${failure.message}")
          }
        }
        checkCancellation()
        FunctionAccessPolicyObservation(this, finalState.accessPolicies).use { policies ->
          runToExit()
          finalState.verify(this)
          policies.expectAllowed()
        }
        for ((boundary, trace) in publications) {
          val visits = boundaries.count { it == boundary }
          expectCondition(
              trace.snapshot().instructions == visits.toLong(),
              "Expected exactly $visits completed visits to public marker $boundary",
          )
        }
        checkCancellation()
      }
}
