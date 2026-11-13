package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.basic.CompactComparison
import edu.missouristate.mars.mips.instructions.basic.InstrCompactCompareBranch
import edu.missouristate.mars.mips.instructions.basic.release6FloatFactories
import java.io.BufferedReader
import java.io.IOException
import java.util.List.copyOf
import java.util.StringTokenizer

/** Loads the basic instruction catalog and pseudo-instruction templates. */
class InstructionLoader {
    fun loadBasicInstructions(): List<BasicInstruction> =
        copyOf((basicInstructionFactories + release6FloatFactories).map { it() } +
            CompactComparison.entries.map(::InstrCompactCompareBranch))

    fun loadExtendedInstructions(owner: InstructionSet): List<ExtendedInstruction> = try {
        pseudoOps().useLines { lines ->
            copyOf(lines.filterNot { it.isEmpty() || it.startsWith('#') || it.startsWith(' ') }
                .map { parseTemplate(it, owner) }.toList())
        }
    } catch (failure: IOException) {
        throw IllegalStateException("Unable to load pseudo-instruction templates", failure)
    } catch (failure: RuntimeException) {
        throw IllegalStateException("Unable to load pseudo-instruction templates", failure)
    }

    private fun parseTemplate(line: String, owner: InstructionSet): ExtendedInstruction {
        val tokens = StringTokenizer(line, "\t")
        val example = tokens.nextToken()
        val mnemonic = example.substringBefore(' ')
        val wide = owner.target == MarsTarget.MARS64
        if (wide) {
            when (mnemonic) {
                "ld", "sd" -> return wideMemoryTemplate(owner, mnemonic, example)
                "move" -> return ExtendedInstruction(example, "or RG1, RG2, \$0", null, "Copy an integer register", owner)
            }
        }
        var description = ""
        var standard: String? = null
        var template = StringBuilder()
        while (tokens.hasMoreTokens()) {
            val token = tokens.nextToken()
            when {
                token.startsWith('#') -> {
                    description = token.substring(1)
                    break
                }
                token.startsWith("COMPACT") -> {
                    standard = template.toString()
                    template = StringBuilder()
                }
                else -> {
                    val instruction = if (wide && (mnemonic == "seq" || mnemonic == "sne") && token.startsWith("subu ")
                    ) {
                        // An equality test must detect a difference anywhere in the register.
                        token.replaceFirst("subu ", "xor ")
                    } else token
                    template.append(instruction)
                    if (tokens.hasMoreTokens()) template.append('\n')
                }
            }
        }
        val remaining = template.toString()
        return if (standard == null) {
            ExtendedInstruction(example, remaining, null, description, owner)
        } else {
            ExtendedInstruction(example, standard, remaining, description, owner)
        }
    }

    private fun wideMemoryTemplate(owner: InstructionSet, mnemonic: String, example: String): ExtendedInstruction {
        val setup = when (example.substringAfter(',')) {
            "-100(\$t2)" -> "$mnemonic RG1, VL2(RG4)"
            "100000" -> "lui \$1, VH2\n$mnemonic RG1, VL2(\$1)"
            "label" -> "lui \$1, LH2\n$mnemonic RG1, LL2(\$1)"
            "label+100000" -> "lui \$1, LHPA\n$mnemonic RG1, LLP(\$1)"
            "(\$t2)" -> "$mnemonic RG1, 0(RG3)"
            "100000(\$t2)" -> "lui \$1, VH2\ndaddu \$1, \$1, RG4\n$mnemonic RG1, VL2(\$1)"
            "label(\$t2)" -> "lui \$1, LH2\ndaddu \$1, \$1, RG4\n$mnemonic RG1, LL2(\$1)"
            "label+100000(\$t2)" -> "lui \$1, LHPA\ndaddu \$1, \$1, RG6\n$mnemonic RG1, LLP(\$1)"
            else -> error("Unknown doubleword operand form: $example")
        }
        val description = if (mnemonic == "ld") "Load an aligned doubleword" else "Store an aligned doubleword"
        return ExtendedInstruction(example, setup, null, description, owner)
    }

    private fun pseudoOps(): BufferedReader {
        val input = InstructionLoader::class.java.getResourceAsStream("/PseudoOps.txt")
            ?: throw IllegalStateException("Missing instruction resource: /PseudoOps.txt")
        return input.bufferedReader(Charsets.UTF_8)
    }
}
