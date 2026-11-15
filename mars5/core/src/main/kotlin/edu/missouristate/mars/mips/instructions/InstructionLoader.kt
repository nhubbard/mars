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
            CompactComparison.entries.filterNot { it.wideOnly }.map(::InstrCompactCompareBranch))

    fun loadExtendedInstructions(owner: InstructionSet): List<ExtendedInstruction> = try {
        pseudoOps().useLines { lines ->
            val templates = lines.filterNot { it.isEmpty() || it.startsWith('#') || it.startsWith(' ') }
                .mapNotNull { parseTemplate(it, owner) }.toMutableList()
            if (owner.target == MarsTarget.MARS64) {
                templates.add(wideConstantTemplate(owner, "dli \$t1,0x123456789abcdef0"))
                templates.add(wideConstantTemplate(owner, "dla \$t1,label"))
                templates.addAll(release6TrapTemplates(owner))
                templates.addAll(release6CompactAliases(owner))
                templates.add(ExtendedInstruction(
                    "addi \$t1,\$t2,-100", "addiu \$at, \$zero, VL3\nadd RG1, RG2, \$at", null,
                    "Add a signed immediate and trap on overflow. Uses \$at as a temporary.", owner,
                ))
                for ((alias, operation) in unalignedDoublewords) {
                    for (operand in listOf("-100(\$t2)", "(\$t2)", "100000", "100000(\$t2)")) {
                        templates.add(wideMemoryTemplate(owner, operation, "$alias \$t1,$operand"))
                    }
                }
                for ((mnemonic, operation) in wideMemoryOperations) {
                    val register = if (operation in floatingMemoryOperations) "\$f2" else "\$t1"
                    templates.add(WideAddressMemory("$mnemonic $register,0x100000000", operation, 0, false, owner))
                    templates.add(WideAddressMemory("$mnemonic $register,0x100000000(\$t2)", operation, 4, false, owner))
                    if (operation in extraSymbolicMemoryOperations) {
                        for (operand in listOf("label", "label(\$t2)", "label+100000", "label+100000(\$t2)")) {
                            templates.add(wideSymbolicMemory(owner, "$mnemonic $register,$operand", operation, operand))
                        }
                    }
                }
            }
            copyOf(templates)
        }
    } catch (failure: IOException) {
        throw IllegalStateException("Unable to load pseudo-instruction templates", failure)
    } catch (failure: RuntimeException) {
        throw IllegalStateException("Unable to load pseudo-instruction templates", failure)
    }

    private fun release6TrapTemplates(owner: InstructionSet): List<ExtendedInstruction> =
        listOf("teq", "tge", "tgeu", "tlt", "tltu", "tne").map { Release6ImmediateTrap(it, owner) }

    private fun release6CompactAliases(owner: InstructionSet): List<ExtendedInstruction> =
        mapOf("bgtc" to "bltc", "blec" to "bgec", "bgtuc" to "bltuc", "bleuc" to "bgeuc").map { (alias, form) ->
            ExtendedInstruction(
                "$alias \$t1,\$t2,label", "$form RG2, RG1, LAB", null,
                "Compact comparison branch. Assembles as $form with operands reversed.", owner,
            )
        } + listOf(
            ExtendedInstruction("jrc \$t1", "jic RG1, 0", null, "Jump to a register without a delay slot", owner),
            ExtendedInstruction("jalrc \$t1", "jialc RG1, 0", null, "Call a register without a delay slot", owner),
        )

    private fun parseTemplate(line: String, owner: InstructionSet): ExtendedInstruction? {
        val tokens = StringTokenizer(line, "\t")
        val example = tokens.nextToken()
        val mnemonic = example.substringBefore(' ')
        val wide = owner.target == MarsTarget.MARS64
        if (wide) {
            val operand = example.substringAfter(',')
            val operation = wideMemoryOperations[mnemonic]
            if (operation != null && operand.startsWith("label")) {
                return wideSymbolicMemory(owner, example, operation, operand)
            }
            when (mnemonic) {
                "la" -> if (example == "la \$t1,label") return wideConstantTemplate(owner, example)
                "ld", "sd" -> return wideMemoryTemplate(owner, mnemonic, example)
                "ulw", "usw", "ulh", "ulhu", "ush" ->
                    return wideMemoryTemplate(owner, mnemonic.removePrefix("u"), example)
                "lwl", "lwr", "swl", "swr" -> return null
                "move" -> return ExtendedInstruction(example, "or RG1, RG2, \$0", null, "Copy an integer register", owner)
                "mul", "mulu", "div", "divu", "rem", "remu" -> return wideWordArithmeticTemplate(owner, mnemonic, example)
                "mulo", "mulou", "mfc1.d", "mtc1.d" -> return null
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
                    val instruction = if (wide) wideTemplateInstruction(mnemonic, token) else token
                    template.append(instruction)
                    if (tokens.hasMoreTokens()) template.append('\n')
                }
            }
        }
        if (wide && (mnemonic == "ll" || mnemonic == "sc")) {
            description = if (mnemonic == "ll") "Load and reserve a word" else "Store a word if its reservation is held"
        }
        if (wide && mnemonic in doublewordFloatingMemoryPseudos) {
            description = if (mnemonic == "ldc1" || mnemonic == "l.d") "Load a floating-point doubleword"
            else "Store a floating-point doubleword"
        }
        val remaining = template.toString()
        return if (standard == null) {
            ExtendedInstruction(example, remaining, null, description, owner)
        } else {
            ExtendedInstruction(example, standard, remaining, description, owner)
        }
    }

    private fun wideConstantTemplate(owner: InstructionSet, example: String): ExtendedInstruction =
        ExtendedInstruction(
            example, loadWideConstant("RG1"), null, "Load a full-width constant or label address", owner,
        )

    private fun wideSymbolicMemory(owner: InstructionSet, example: String, operation: String, operand: String): ExtendedInstruction {
        val hasAddend = '+' in operand
        val base = if ('(' !in operand) 0 else if (hasAddend) 6 else 4
        return WideAddressMemory(example, operation, base, hasAddend, owner)
    }

    private fun wideWordArithmeticTemplate(owner: InstructionSet, mnemonic: String, example: String): ExtendedInstruction? {
        val operand = example.substringAfterLast(',')
        val operation = when (mnemonic) {
            "rem" -> "mod"
            "remu" -> "modu"
            else -> mnemonic
        }
        val description = when (operation) {
            "mul", "mulu" -> "Multiply words and keep the low word"
            "div", "divu" -> "Divide words and keep the quotient"
            else -> "Divide words and keep the remainder"
        }
        val translation = when (operand) {
            "\$t3" -> if (operation == mnemonic) return null else "$operation RG1, RG2, RG3"
            "-100" -> "addiu \$1, \$0, VL3\n$operation RG1, RG2, \$1"
            "100000" -> "lui \$1, VHL3\nori \$1, \$1, VL3U\n$operation RG1, RG2, \$1"
            else -> error("Unknown word arithmetic operand form: $example")
        }
        return ExtendedInstruction(example, translation, null, description, owner)
    }

    private fun wideTemplateInstruction(mnemonic: String, token: String): String = when {
        (mnemonic == "ll" || mnemonic == "sc") && token.startsWith("$mnemonic ") -> {
            val register = token.substringAfter(' ').substringBefore(',')
            val offset = token.substringAfter(',').substringBefore('(').trim()
            val base = token.substringAfter('(').substringBefore(')')
            if (offset == "0") token else "daddiu \$1, $base, $offset\n$mnemonic $register,0(\$1)"
        }
        token.startsWith("addu ") && (mnemonic == "ll" || mnemonic == "sc" || mnemonic in doublewordFloatingMemoryPseudos) ->
            token.replaceFirst("addu ", "daddu ")
        (mnemonic == "seq" || mnemonic == "sne") && token.startsWith("subu ") -> {
            // An equality test must detect a difference anywhere in the register.
            token.replaceFirst("subu ", "xor ")
        }
        mnemonic == "b" && token == "bgez \$0, LAB" -> "beq \$0, \$0, LAB"
        token.startsWith("addi ") -> {
            val (destination, source, immediate) = token.removePrefix("addi ").split(", ")
            when {
                mnemonic == "la" -> "daddiu $destination, $source, $immediate"
                source == "\$0" -> "addiu $destination, $source, $immediate"
                else -> "addiu \$1, \$0, $immediate\nadd $destination, $source, \$1"
            }
        }
        else -> token
    }

    private fun wideMemoryTemplate(owner: InstructionSet, mnemonic: String, example: String): ExtendedInstruction {
        val setup = when (example.substringAfter(',')) {
            "-100(\$t2)" -> "$mnemonic RG1, VL2(RG4)"
            "100000" -> "lui \$1, VH2\n$mnemonic RG1, VL2(\$1)"
            "(\$t2)" -> "$mnemonic RG1, 0(RG3)"
            "100000(\$t2)" -> "lui \$1, VH2\ndaddu \$1, \$1, RG4\n$mnemonic RG1, VL2(\$1)"
            else -> error("Unknown memory operand form: $example")
        }
        val description = when (example.substringBefore(' ')) {
            "ld" -> "Load a doubleword"
            "sd" -> "Store a doubleword"
            else -> "Alias for $mnemonic, which also accepts unaligned addresses"
        }
        return ExtendedInstruction(example, setup, null, description, owner)
    }

    private companion object {
        val doublewordFloatingMemoryPseudos = setOf("ldc1", "sdc1", "l.d", "s.d")
        val floatingMemoryOperations = setOf("lwc1", "swc1", "ldc1", "sdc1")
        val unalignedDoublewords = mapOf("uld" to "ld", "usd" to "sd")
        val extraSymbolicMemoryOperations = setOf("lwu", "lld", "scd") + unalignedDoublewords.keys
        val wideMemoryOperations = (
            listOf("lb", "lbu", "lh", "lhu", "lw", "lwu", "ld", "sb", "sh", "sw", "sd", "ll", "sc", "lld", "scd") +
                floatingMemoryOperations
            ).associateWith { it } + mapOf(
            "l.s" to "lwc1", "s.s" to "swc1", "l.d" to "ldc1", "s.d" to "sdc1",
            "ulw" to "lw", "usw" to "sw", "ulh" to "lh", "ulhu" to "lhu", "ush" to "sh",
        ) + unalignedDoublewords
    }

    private fun pseudoOps(): BufferedReader {
        val input = InstructionLoader::class.java.getResourceAsStream("/PseudoOps.txt")
            ?: throw IllegalStateException("Missing instruction resource: /PseudoOps.txt")
        return input.bufferedReader(Charsets.UTF_8)
    }
}

private fun loadWideConstant(register: String, hasAddend: Boolean = false): String {
    val suffix = if (hasAddend) "A" else ""
    return "ori $register, \$zero, DW3_2$suffix\n" +
        (2 downTo 0).joinToString("\n") { "dsll $register, $register, 16\nori $register, $register, DW${it}_2$suffix" }
}

internal class WideAddressMemory(
    example: String, operation: String, val baseToken: Int, hasAddend: Boolean, owner: InstructionSet,
) : ExtendedInstruction(
    example,
    loadWideConstant("\$at", hasAddend) + "\n" +
        (if (baseToken == 0) "" else "daddu \$at, \$at, RG$baseToken\n") +
        "$operation RG1,0(\$at)",
    null, "Access memory at a full-width address using \$at as a temporary", owner,
) {
    val stores = operation in setOf("sb", "sh", "sw", "sd", "sc", "scd", "swc1", "sdc1")
}

internal class Release6ImmediateTrap(operation: String, owner: InstructionSet) : ExtendedInstruction(
    "${if (operation.endsWith('u')) operation.dropLast(1) + "iu" else operation + "i"} \$t1,-100",
    "daddiu \$at, \$zero, VL2\n$operation RG1, \$at", null,
    "Trap after comparing against a signed 16-bit immediate. Uses \$at as a temporary.", owner,
)
