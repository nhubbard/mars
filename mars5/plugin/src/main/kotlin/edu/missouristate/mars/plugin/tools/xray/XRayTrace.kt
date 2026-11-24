package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.NOT_TAKEN
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import edu.missouristate.mars.assembler.TokenTypes.FP_REGISTER_NAME
import edu.missouristate.mars.assembler.TokenTypes.REGISTER_NAME
import edu.missouristate.mars.assembler.TokenTypes.REGISTER_NUMBER
import edu.missouristate.mars.assembler.TokenTypes.VECTOR_REGISTER_NAME
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.util.Binary

/**
 * Holds completed events, including repeated addresses. Backstep rewinds this execution's timeline.
 */
internal class XRayTrace {
  private val steps = ArrayDeque<StepEvent>()
  private val memoryByStep = ArrayDeque<List<MemoryAccessNotice>>()
  private val pendingMemory = ArrayList<MemoryAccessNotice>()
  private var cached: List<StepEvent>? = emptyList()
  private var rewindOnConnect: Long? = null

  @get:Synchronized
  var connected = true
    private set

  @Synchronized
  fun setCaptureEnabled(enabled: Boolean) {
    connected = enabled
    pendingMemory.clear()
    if (enabled) {
      rewindOnConnect?.let(::trimAfter)
      rewindOnConnect = null
    }
  }

  @Synchronized
  fun clear() {
    steps.clear()
    memoryByStep.clear()
    pendingMemory.clear()
    cached = emptyList()
    rewindOnConnect = null
  }

  @Synchronized
  fun record(event: StepEvent) {
    if (!connected) return
    if (steps.size == CAPACITY) {
      steps.removeFirst()
      memoryByStep.removeFirst()
    }
    memoryByStep.addLast(pendingMemory.toList())
    pendingMemory.clear()
    steps.addLast(event)
    cached = null
  }

  @Synchronized
  fun rewind(stepCount: Long) {
    pendingMemory.clear()
    if (connected) {
      trimAfter(stepCount)
    } else {
      // Leave the trace available for review until capture resumes.
      rewindOnConnect = minOf(rewindOnConnect ?: stepCount, stepCount)
    }
  }

  private fun trimAfter(stepCount: Long) {
    while ((steps.lastOrNull()?.stepCount ?: 0) > stepCount) {
      steps.removeLast()
      memoryByStep.removeLast()
      cached = null
    }
  }

  fun observeMemory(memory: MemoryState): AutoCloseable {
    val callback = ::recordMemory
    memory.addAddressObserver(callback, includeInstructionFetch = false)
    return AutoCloseable { memory.deleteAddressObserver(callback) }
  }

  @Synchronized
  private fun recordMemory(notice: MemoryAccessNotice) {
    if (connected && notice.origin() == EXECUTION && pendingMemory.size < MEMORY_LIMIT) {
      pendingMemory.add(notice)
    }
  }

  @Synchronized
  fun memoryFor(event: StepEvent): List<MemoryAccessNotice> {
    for (index in steps.lastIndex downTo 0) {
      if (steps[index] == event) return memoryByStep[index]
    }
    return emptyList()
  }

  @Synchronized fun snapshot(): List<StepEvent> = cached ?: steps.toList().also { cached = it }

  companion object {
    const val CAPACITY = 128
    private const val MEMORY_LIMIT = 8
  }
}

/**
 * Formats the executed machine word using the core catalog, independent of mutable simulator
 * memory.
 */
internal class XRayInstruction(
    private val event: StepEvent,
    private val memory: List<MemoryAccessNotice> = emptyList(),
) {
  private val statement =
      ProgramStatement(event.instruction, event.pcBits, InstructionDefaults.forTarget(event.target))
  val isWide = event.target == MarsTarget.MARS64
  private val instruction = statement.instruction as? BasicInstruction
  val decoded = instruction != null
  val hasMemoryTraffic = memory.isNotEmpty()
  val registerBanks: List<String> =
      instruction
          ?.tokenList
          ?.let { tokens ->
            (1 until tokens.size())
                .mapNotNull { index ->
                  when (tokens[index].type) {
                    REGISTER_NAME,
                    REGISTER_NUMBER -> "xray.mars64.gpr"
                    FP_REGISTER_NAME -> "xray.mars64.fpu"
                    VECTOR_REGISTER_NAME -> "xray.mars64.msa"
                    else -> null
                  }
                }
                .distinct()
                .map { toolsMessages.getMessage(it) }
          }
          .orEmpty()
  val isControlFlow = event.transfer != null || event.branchOutcome != null
  val word = event.instruction
  val format = instruction?.instructionFormat
  private val mnemonic = instruction?.name ?: toolsMessages.getMessage("xray.unknown")

  fun describe(): String = buildString {
    val address = event.xRayAddress()
    val machine = Binary.intToHexString(word)
    appendLine(
        toolsMessages.getMessage(
            "xray.instructionDetails",
            address,
            machine,
            statement.printableBasicAssemblyStatement.trimEnd(),
        ),
    )
    val bits = Binary.intToBinaryString(word)
    val fields =
        if (isWide) encodingFields(bits)
        else
            when (format) {
              R_FORMAT ->
                  toolsMessages.getMessage(
                      "xray.fields.register",
                      bits.substring(0, 6),
                      bits.substring(6, 11),
                      bits.substring(11, 16),
                      bits.substring(16, 21),
                      bits.substring(21, 26),
                      bits.substring(26),
                  )
              I_FORMAT,
              I_BRANCH_FORMAT ->
                  toolsMessages.getMessage(
                      "xray.fields.immediate",
                      bits.substring(0, 6),
                      bits.substring(6, 11),
                      bits.substring(11, 16),
                      bits.substring(16),
                  )
              J_FORMAT ->
                  toolsMessages.getMessage(
                      "xray.fields.jump",
                      bits.substring(0, 6),
                      bits.substring(6),
                  )
              null -> bits
            }
    append(fields)
    if (isWide) {
      if (registerBanks.isNotEmpty())
          appendLine()
              .append(
                  toolsMessages.getMessage(
                      "xray.mars64.operands",
                      registerBanks.joinToString(", "),
                  ),
              )
      event.nextPcBits?.let {
        appendLine()
            .append(toolsMessages.getMessage("xray.mars64.next", Binary.longToHexString(it)))
      }
      if (event.branchOutcome != null) {
        statement.relativeBranchDestination()?.let {
          appendLine()
              .append(
                  toolsMessages.getMessage("xray.mars64.destination", Binary.longToHexString(it)),
              )
        }
      }
      for (access in memory) {
        val action = if (access.accessType == WRITE) "xray.mars64.store" else "xray.mars64.load"
        appendLine()
            .append(
                toolsMessages.getMessage(
                    action,
                    Binary.longToHexString(access.addressBits),
                    access.length,
                ),
            )
      }
    }
    when (event.branchOutcome) {
      TAKEN ->
          appendLine()
              .append(
                  toolsMessages.getMessage(
                      "xray.branch",
                      toolsMessages.getMessage("branchHistory.taken"),
                  ),
              )
      NOT_TAKEN ->
          appendLine()
              .append(
                  toolsMessages.getMessage(
                      "xray.branch",
                      toolsMessages.getMessage("branchHistory.notTaken"),
                  ),
              )
      null -> Unit
    }
  }

  private fun encodingFields(bits: String): String {
    val mask = instruction?.operationMask ?: return bits
    val fields = buildList {
      var start = 0
      while (start < mask.length) {
        val field = mask[start].let { if (it == '0' || it == '1') '0' else it }
        var end = start + 1
        while (end < mask.length && (mask[end] == field || field == '0' && mask[end] == '1')) end++
        add(bits.substring(start, end))
        start = end
      }
    }
    return toolsMessages.getMessage("xray.mars64.fields", fields.joinToString(" · "))
  }

  fun signalLabel(name: String): String =
      when (name) {
        "ALUVALUE" -> mnemonic
        "CP+4" ->
            if (event.branchOutcome == TAKEN) toolsMessages.getMessage("xray.pc.offset")
            else "PC + 4"
        "READING" -> toolsMessages.getMessage("xray.register.read")
        "WRITING" -> toolsMessages.getMessage("xray.register.write")
        else -> name
      }
}

internal fun StepEvent.xRayAddress(): String =
    if (target == MarsTarget.MARS64) Binary.longToHexString(pcBits) else Binary.intToHexString(pc)

internal class XRayPlayback {
  var playing = false
    private set

  var position = 0
    private set

  private var end = 0

  fun show(duration: Int) {
    end = duration
    playing = false
    position = end
  }

  fun toggle() {
    if (playing) {
      playing = false
    } else {
      if (position == end) position = 0
      playing = true
    }
  }

  fun reset() {
    position = 0
    playing = false
  }

  fun advance(ticks: Int): Boolean {
    if (!playing) return false
    position = minOf(end, position + ticks)
    if (position == end) playing = false
    return true
  }
}
