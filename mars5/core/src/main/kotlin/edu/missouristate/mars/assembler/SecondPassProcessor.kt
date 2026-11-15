package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.util.NumberDisplayBase
import edu.missouristate.mars.util.Binary
import java.util.Collections.newSetFromMap
import java.util.IdentityHashMap

/** Resolves operands, expands pseudo-instructions, and emits machine words. */
internal class SecondPassProcessor(
    private val context: AssemblyContext,
    private val checkCancellation: () -> Unit,
    private val symbolUses: AssemblySymbolUses,
) {
    data class Result(val instructions: ArrayList<ProgramStatement>, val sourceMap: InstructionSourceMap)

    @Throws(ProcessingException::class)
    fun process(programs: ArrayList<MIPSprogram>, errors: ErrorList, warningsAreErrors: Boolean): Result {
        val machine = ArrayList<ProgramStatement>()
        val pseudoAddresses = HashSet<Long>()
        val expander = PseudoInstructionExpander(context)
        for (program in programs) {
            if (errors.errorLimitExceeded()) break
            for (statement in program.parsedList!!) {
                checkCancellation()
                statement.buildBasicStatementFromBasicInstruction(errors, symbolUses::record)
                if (errors.errorsOccurred()) throw ProcessingException(errors)
                if (statement.instruction is BasicInstruction) {
                    machine.add(statement)
                } else {
                    for (generated in expander.expand(statement, program, errors)) {
                        checkCancellation()
                        pseudoAddresses.add(generated.addressBits)
                        machine.add(generated)
                    }
                }
            }
        }

        val origins = HashMap<Long, Origin>()
        val breakpoints = HashMap<Location, MutableList<Long>>()
        val seen = newSetFromMap(IdentityHashMap<Origin, Boolean>())
        for (statement in machine) {
            checkCancellation()
            if (errors.errorLimitExceeded()) break
            statement.buildMachineStatementFromBasicStatement(errors)
            val address = statement.addressBits
            val origin = statement.instructionOrigin!!
            origins[address] = origin
            // Record the first emitted address for each occurrence before sorting by address.
            var site: Origin? = origin
            while (site != null && seen.add(site)) {
                breakpoints.getOrPut(site.location, ::ArrayList).add(address)
                site = site.caller
            }
            try {
                context.memory().setStatement(address, statement)
            } catch (error: AddressErrorException) {
                errors.add(ErrorMessage(statement.originalTokenList!![0], "Invalid address for text segment: ${error.addressBits}"))
            }
        }

        machine.sortWith { first, second ->
            checkCancellation()
            first.addressBits.toULong().compareTo(second.addressBits.toULong())
        }
        checkDuplicateAddresses(machine, errors)
        if (errors.errorsOccurred() || errors.warningsOccurred() && warningsAreErrors) throw ProcessingException(errors)
        return Result(machine, InstructionSourceMap.fromAddressBits(origins, breakpoints, pseudoAddresses))
    }

    private fun checkDuplicateAddresses(instructions: List<ProgramStatement>, errors: ErrorList) {
        for (index in 0 until instructions.lastIndex) {
            checkCancellation()
            val first = instructions[index]
            val second = instructions[index + 1]
            if (first.addressBits == second.addressBits) {
                val address = if (second.addressBits == second.address.toLong()) {
                    NumberDisplayBase.formatUnsignedInteger(second.address, NumberDisplayBase.getBase(context.displayAddressesInHex()))
                } else if (context.displayAddressesInHex()) {
                    Binary.longToHexString(second.addressBits)
                } else second.addressBits.toULong().toString()
                val directive = if (context.memory().inWideText(second.addressBits) || context.memory().inTextSegment(second.address)) ".text" else ".ktext"
                errors.add(
                    ErrorMessage(
                        second.sourceMIPSprogram, second.sourceLine, 0,
                        "Duplicate text segment address: $address already occupied by ${first.sourceFile} " +
                            "line ${first.sourceLine} (caused by use of $directive operand)",
                    ),
                )
            }
        }
    }
}
