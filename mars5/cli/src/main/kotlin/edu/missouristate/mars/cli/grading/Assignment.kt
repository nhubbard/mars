@file:Suppress("unused")

package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.StopEvent.Reason
import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.cli.HeadlessGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CancellationException

@DslMarker
annotation class AssignmentDsl

internal data class CaseResult(val name: String, val failure: String?)

@AssignmentDsl
class Assignment internal constructor(
    private val submission: AssemblySourceSnapshot,
) {
    internal val results = mutableListOf<CaseResult>()

    fun case(
        name: String,
        instructionLimit: Long = 100_000,
        input: String = "",
        utf8Text: Boolean = false,
        target: MarsTarget = MarsTarget.DEFAULT,
        verify: AssemblyCase.() -> Unit,
    ) {
        require(name.isNotBlank() && results.none { it.name == name }) { "Choose a unique, non-empty case name" }
        require(instructionLimit > 0) { "Instruction limit must be positive" }
        try {
            DaggerMarsComponent.builder().guiProxy(HeadlessGuiProxy()).build().context().use { machine ->
                machine.selectTarget(target)
                machine.settings.isUtf8TextEnabled = utf8Text
                var assemblyChecks = 0
                val program = machine.assembleSnapshot(submission, null, extended = true, warnings = false) {
                    if (++assemblyChecks > 1_000_000) throw AssemblyLimitReached()
                }
                val captured = ByteArrayOutputStream()
                PrintStream(BoundedOutput(captured), true, Charsets.UTF_8).use { output ->
                    SessionSyscallEnvironment(
                        machine.memory, ByteArrayInputStream(input.encodeToByteArray()), output, output,
                        inputCharset = Charsets.UTF_8,
                    ).use { console ->
                        program.setSyscallEnvironment(GradingEnvironment(console))
                        machine.simulator().setInstructionLimit(instructionLimit)
                        AssemblyCase(machine, program.symbolMap(), captured).verify()
                    }
                }
            }
            results += CaseResult(name, null)
        } catch (failure: CaseFailure) {
            results += CaseResult(name, failure.message)
        } catch (_: AssemblyLimitReached) {
            results += CaseResult(name, "Submission exceeds the assembly work limit")
        } catch (failure: ProcessingException) {
            results += CaseResult(name, failure.errors()?.generateErrorAndWarningReport()?.trim() ?: failure.toString())
        }
    }
}

@AssignmentDsl
class AssemblyCase internal constructor(
    private val machine: MarsContext,
    private val symbols: AssemblySymbolMap,
    private val output: ByteArrayOutputStream,
) {
    private val simulator = machine.simulator()
    private var stop: StopEvent? = null
    private var error: String? = null

    init {
        simulator.addListener(object : SimulatorListener {
            override fun onError(event: ErrorEvent) {
                error = event.message
            }
            override fun onStep(event: StepEvent) = Unit
            override fun onStop(event: StopEvent) = Unit
        })
    }

    fun requireTextLabel(label: String) {
        textAddress(label)
    }

    fun requireDataLabel(label: String) {
        dataAddress(label, 0, alignment = 1)
    }

    fun startAt(label: String) {
        requireCase(stop == null) { "Choose an entry label before executing instructions" }
        val address = textAddress(label)
        machine.claimStoppedSession().use {
            machine.registerFile.setProgramCounterBits(address)
        }
    }

    fun setRegister(register: String, value: Int) {
        setRegister(register, value.toLong())
    }

    fun setRegister(register: String, value: Long) {
        val number = registerNumber(register)
        requireCase(number != 0) { "Cannot write the zero register" }
        requireCase(machine.target == MarsTarget.MARS64 || value == value.toInt().toLong()) { "Value does not fit a MARS32 register" }
        machine.editIntegerRegister(number, value)
    }

    fun setWord(label: String, value: Int, offset: Int = 0) {
        val address = dataAddress(label, offset)
        try {
            machine.editMemoryWord(address, value)
        } catch (failure: AddressErrorException) {
            throw CaseFailure(failure.message ?: "Cannot write word at $label")
        }
    }

    fun step(instructions: Long = 1) {
        require(instructions > 0) { "Step count must be positive" }
        ensureRunnable()
        checkStop(simulator.runAsync(instructions, null).join(), Reason.STEP_LIMIT)
    }

    /** Pause before executing the instruction at [label]. */
    fun runUntil(label: String) {
        ensureRunnable()
        val address = textAddress(label)
        checkStop(simulator.runUntilAddressAsync(0) { it == address }.join(), Reason.BREAKPOINT)
    }

    fun runToExit(exitCode: Int = 0) {
        ensureRunnable()
        val result = simulator.runAsync(0, null).join()
        checkStop(result, Reason.NORMAL_EXIT)
        requireCase(result.exitCode == exitCode) { "Expected exit code $exitCode, got ${result.exitCode}" }
    }

    fun expectRegister(register: String, expected: Int) {
        expectRegister(register, expected.toLong())
    }

    fun expectRegister(register: String, expected: Long) {
        val actual = machine.registerFile.getLongValueNoNotify(registerNumber(register))
        requireCase(actual == expected) { "Expected $register = $expected, got $actual" }
    }

    /** Check the raw register halves, with lane zero in [low]. */
    fun expectVector(register: String, low: Long, high: Long) {
        val expected = VectorRegister(low, high)
        val actual = vector(register)
        requireCase(actual == expected) { "Expected $register = ${expected.toHexString()}, got ${actual.toHexString()}" }
    }

    fun expectVectorLane(register: String, lane: Int, width: Int, expected: Long) {
        val actual = vector(register).unsignedLane(lane, width)
        requireCase(actual == expected) { "Expected $register lane $lane ($width bits) = $expected, got $actual" }
    }

    private fun vector(register: String): VectorRegister {
        requireCase(machine.target == MarsTarget.MARS64) { "Vector registers require MARS64" }
        val name = "\$${register.removePrefix("\$")}"
        val number = RegisterNames.vector(name) ?: throw CaseFailure("Unknown vector register $register")
        return machine.coprocessor1.getVectorNoNotify(number)
    }

    fun expectWord(label: String, expected: Int, offset: Int = 0) {
        val actual = machine.memory.inspectWord(dataAddress(label, offset))
        requireCase(actual == expected) { "Expected word at $label + $offset = $expected, got $actual" }
    }

    fun expectBytes(label: String, vararg expected: Int, offset: Int = 0) {
        for ((index, byte) in expected.withIndex()) {
            require(byte in 0..255) { "Expected bytes must be unsigned values from 0 through 255" }
            val actual = machine.memory.inspectDataByte(dataAddress(label, offset.toLong() + index, alignment = 1))
            requireCase(actual == byte) { "Expected byte at $label + ${offset.toLong() + index} = $byte, got $actual" }
        }
    }

    fun expectOutput(expected: String) {
        val actual = output.toString(Charsets.UTF_8)
        requireCase(actual == expected) { "Expected output ${expected.quote()}, got ${actual.quote()}" }
    }

    private fun ensureRunnable() {
        val reason = stop?.reason
        requireCase(reason == null || reason == Reason.STEP_LIMIT || reason == Reason.BREAKPOINT) {
            "Execution has already stopped"
        }
    }

    private fun checkStop(result: StopEvent, expected: Reason) {
        stop = result
        requireCase(result.reason == expected) {
            val detail = error?.let { " ($it)" }.orEmpty()
            "Expected execution to reach $expected, stopped at ${result.reason}$detail"
        }
    }

    private fun registerNumber(register: String): Int {
        val name = if (register.startsWith('$')) register else "$$register"
        return machine.registerFile.getUserRegister(name)?.number
            ?: throw CaseFailure("Unknown register: $register")
    }

    private fun definition(label: String): AssemblySymbolMap.Definition {
        symbols.globals()[label]?.let { return it }
        val definitions = symbols.locals().values.mapNotNull { it[label]?.takeUnless(AssemblySymbolMap.Definition::macroPrivate) }
        requireCase(definitions.size <= 1) { "Label is ambiguous: $label" }
        return definitions.singleOrNull() ?: throw CaseFailure("Missing label: $label")
    }

    private fun textAddress(label: String): Long {
        val definition = definition(label)
        val address = definition.addressBits
        requireCase(definition.kind == AssemblySymbolMap.Kind.TEXT && address % Int.SIZE_BYTES == 0L &&
            machine.memory.inspectInstruction(address) != null) {
            "Expected an instruction at text label: $label"
        }
        return address
    }

    private fun dataAddress(label: String, offset: Int): Long = dataAddress(label, offset.toLong(), Int.SIZE_BYTES)

    private fun dataAddress(label: String, offset: Long, alignment: Int): Long {
        val definition = definition(label)
        requireCase(definition.kind == AssemblySymbolMap.Kind.DATA || definition.kind == AssemblySymbolMap.Kind.EXTERNAL) {
            "Expected a data label: $label"
        }
        val base = if (definition.addressBits == definition.address.toLong()) definition.address.toUInt().toLong()
        else definition.addressBits
        val address = base + offset
        requireCase(address % alignment == 0L && machine.memory.inUserData(address)) {
            "Invalid data address at $label + $offset"
        }
        return address
    }
}

internal class CaseFailure(message: String) : RuntimeException(message)

private class AssemblyLimitReached : CancellationException()

internal inline fun requireCase(condition: Boolean, message: () -> String) {
    if (!condition) throw CaseFailure(message())
}

private fun String.quote() = "\"" + take(200).replace("\n", "\\n").replace("\r", "\\r") + "\""

private class BoundedOutput(private val bytes: ByteArrayOutputStream) : OutputStream() {
    override fun write(value: Int) {
        checkCapacity(1)
        bytes.write(value)
    }

    override fun write(values: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        bytes.write(values, offset, length)
    }

    private fun checkCapacity(length: Int) {
        requireCase(length <= 64 * 1024 - bytes.size()) { "Program output exceeds 64 KiB" }
    }
}

private class GradingEnvironment(console: SyscallEnvironment) : SyscallEnvironment by console {
    override fun allowsSyscall(number: Int): Boolean = when (number) {
        in 1..12, 17, in 34..36 -> true
        else -> false
    }
}
