package edu.missouristate.mars.plugin.tools.xray

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.BranchOutcome.NOT_TAKEN
import edu.missouristate.mars.api.StepEvent.BranchOutcome.TAKEN
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.J_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.tools.toolsMessages
import edu.missouristate.mars.util.Binary

/** Holds completed events, including repeated addresses. Backstep rewinds this execution's timeline. */
internal class XRayTrace {
    private val steps = ArrayDeque<StepEvent>()
    private var cached: List<StepEvent>? = emptyList()
    private var rewindOnConnect: Long? = null

    @get:Synchronized
    var connected = true
        private set

    @Synchronized
    fun setCaptureEnabled(enabled: Boolean) {
        connected = enabled
        if (enabled) {
            rewindOnConnect?.let(::trimAfter)
            rewindOnConnect = null
        }
    }

    @Synchronized
    fun clear() {
        steps.clear()
        cached = emptyList()
        rewindOnConnect = null
    }

    @Synchronized
    fun record(event: StepEvent) {
        if (!connected) return
        if (steps.size == capacity) steps.removeFirst()
        steps.addLast(event)
        cached = null
    }

    @Synchronized
    fun rewind(stepCount: Long) {
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
            cached = null
        }
    }

    @Synchronized
    fun snapshot(): List<StepEvent> = cached ?: steps.toList().also { cached = it }

    companion object {
        const val capacity = 128
    }
}

/** Formats the executed machine word using the core catalog, independent of mutable simulator memory. */
internal class XRayInstruction(private val event: StepEvent) {
    private val statement = ProgramStatement(event.instruction, event.pc, InstructionDefaults.get())
    private val instruction = statement.instruction as? BasicInstruction
    val word = event.instruction
    val format = instruction?.instructionFormat
    private val mnemonic = instruction?.name ?: toolsMessages.getMessage("xray.unknown")

    fun describe(): String = buildString {
        val address = Binary.intToHexString(event.pc)
        val machine = Binary.intToHexString(word)
        appendLine(toolsMessages.getMessage("xray.instructionDetails", address, machine, statement.printableBasicAssemblyStatement.trimEnd()))
        val bits = Binary.intToBinaryString(word)
        val fields = when (format) {
            R_FORMAT -> toolsMessages.getMessage("xray.fields.register",
                bits.substring(0, 6), bits.substring(6, 11), bits.substring(11, 16), bits.substring(16, 21),
                bits.substring(21, 26), bits.substring(26))
            I_FORMAT, I_BRANCH_FORMAT -> toolsMessages.getMessage("xray.fields.immediate",
                bits.substring(0, 6), bits.substring(6, 11), bits.substring(11, 16), bits.substring(16))
            J_FORMAT -> toolsMessages.getMessage("xray.fields.jump", bits.substring(0, 6), bits.substring(6))
            null -> bits
        }
        append(fields)
        when (event.branchOutcome) {
            TAKEN -> appendLine().append(toolsMessages.getMessage("xray.branch", toolsMessages.getMessage("branchHistory.taken")))
            NOT_TAKEN -> appendLine().append(toolsMessages.getMessage("xray.branch", toolsMessages.getMessage("branchHistory.notTaken")))
            null -> Unit
        }
    }

    fun signalLabel(name: String): String = when (name) {
        "ALUVALUE" -> mnemonic
        "CP+4" -> if (event.branchOutcome == TAKEN) toolsMessages.getMessage("xray.pc.offset") else "PC + 4"
        "READING" -> toolsMessages.getMessage("xray.register.read")
        "WRITING" -> toolsMessages.getMessage("xray.register.write")
        else -> name
    }
}

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
