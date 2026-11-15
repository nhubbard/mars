package edu.missouristate.mars.cli

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryAddressRange
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import edu.missouristate.mars.mips.hardware.Register
import edu.missouristate.mars.util.Binary
import java.io.PrintStream

/** Register and memory selections for one command-line invocation. */
internal class CliMachineReport {
    enum class Format { HEX, DECIMAL, ASCII }

    private data class RegisterSelection(val label: String, val register: Register, val floating: Boolean)

    private val registers = ArrayList<RegisterSelection>()
    private val ranges = ArrayList<Pair<String, MemoryAddressRange>>()
    private var instructions = 0L
    var format = Format.HEX
    var brief = false
    var countInstructions = false

    fun tryAddRegister(name: String, session: MarsContext): Boolean {
        val label = if (name.startsWith('$')) name else "$$name"
        if (label.length == 1 || label == "\$f") return false
        val integer = session.registerFile.getUserRegister(label)
        if (integer != null) {
            registers.add(RegisterSelection(label, integer, false))
            return true
        }
        val floating = session.coprocessor1.getRegister(label) ?: return false
        registers.add(RegisterSelection(label, floating, true))
        return true
    }

    fun requireRegister(name: String, session: MarsContext) {
        require(tryAddRegister(name, session)) { "Invalid register name: $name" }
    }

    fun addRange(text: String) {
        ranges.add(text to MemoryAddressRange.parse(text))
    }

    fun validateRanges(memory: MemoryState) {
        for ((argument, range) in ranges) {
            require(range.last <= memory.highestInspectableAddress) { "Invalid memory range for selected target: $argument" }
        }
    }

    fun recordInstructionCount(completed: Long) {
        instructions = completed
    }

    fun write(session: MarsContext, out: PrintStream) {
        if (countInstructions) out.println("\n$instructions")
        else if (registers.isEmpty() && ranges.isEmpty()) return
        out.println()
        for (selection in registers) {
            if (!brief) out.print("${selection.label}\t")
            out.println(registerValue(selection, session))
        }
        for ((_, range) in ranges) writeRange(range, session.memory, out)
    }

    private fun registerValue(selection: RegisterSelection, session: MarsContext): String {
        val register = selection.register
        val bits = register.value
        if (!selection.floating) {
            val wide = session.registerFile.getLongValueNoNotify(register.number)
            return when (format) {
                Format.HEX -> Binary.longToHexString(wide)
                Format.DECIMAL -> wide.toString()
                Format.ASCII -> Binary.intToAscii(Binary.highOrderLongToInt(wide)) + Binary.intToAscii(bits)
            }
        }
        val value = if (format == Format.DECIMAL) Float.fromBits(bits).toString() else formatWord(bits)
        val doubleword = session.coprocessor1.getDoublewordNoNotify(register.number)
        return "$value\t" + when (format) {
            Format.HEX -> Binary.longToHexString(doubleword)
            Format.DECIMAL -> Double.fromBits(doubleword).toString()
            Format.ASCII -> Binary.intToAscii(Binary.highOrderLongToInt(doubleword)) + Binary.intToAscii(bits)
        }
    }

    private fun writeRange(range: MemoryAddressRange, memory: MemoryState, out: PrintStream) {
        var column = 0
        for (address in range.first..range.last step WORD_LENGTH_BYTES.toLong()) {
            val wordAddress = address.toInt()
            if (column == 0 && !brief) out.print("Mem[${CliMemoryDump.formatAddress(address)}]\t")
            try {
                val bits = when {
                    address > UInt.MAX_VALUE.toLong() -> memory.inspectWord(address)
                    memory.inTextSegment(wordAddress) || memory.inKernelTextSegment(wordAddress) ->
                        memory.getRawWordOrNull(wordAddress) ?: 0
                    else -> memory.getWordNoNotify(wordAddress)
                }
                out.print((bits?.let(::formatWord) ?: "Invalid address: $address") + "\t")
            } catch (_: AddressErrorException) {
                out.print("Invalid address: $wordAddress\t")
            }
            column = (column + 1) % 4
            if (column == 0 || address == range.last) out.println()
        }
    }

    private fun formatWord(bits: Int): String = when (format) {
        Format.HEX -> Binary.intToHexString(bits)
        Format.DECIMAL -> bits.toString()
        Format.ASCII -> Binary.intToAscii(bits)
    }
}
