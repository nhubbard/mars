package edu.missouristate.mars.mips.instructions

import java.io.BufferedReader
import java.io.IOException
import java.util.List.copyOf
import java.util.StringTokenizer

/** Loads the basic instruction catalog and pseudo-instruction templates. */
class InstructionLoader {
    fun loadBasicInstructions(): List<BasicInstruction> = copyOf(basicInstructionFactories.map { it() })

    fun loadExtendedInstructions(owner: InstructionSet): List<ExtendedInstruction> = try {
        resource("/PseudoOps.txt").useLines { lines ->
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
                    template.append(token)
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

    private fun resource(path: String): BufferedReader {
        val input = InstructionLoader::class.java.getResourceAsStream(path)
            ?: throw IllegalStateException("Missing instruction resource: $path")
        return input.bufferedReader(Charsets.UTF_8)
    }
}
