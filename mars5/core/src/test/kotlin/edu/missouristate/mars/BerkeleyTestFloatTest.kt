package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BerkeleyTestFloatTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("groups")
    fun `scalar and MSA floating point match TestFloat vectors`(group: Group, @TempDir directory: Path) {
        val operation = group.operation
        for (vector in listOf(false, true)) {
            val assembly = operation.instruction(vector) ?: continue
            val path = Files.writeString(directory.resolve("probe.s"), "$assembly\n")
            TestSessions.create().use { machine ->
                machine.selectTarget(MarsTarget.MARS64)
                val program = machine.assemble(listOf(path), null, extended = false, warnings = false)
                val fpu = machine.coprocessor1
                val pc = machine.registerFile.programCounter
                for (case in group.cases) {
                    machine.registerFile.setProgramCounter(pc)
                    fpu.updateFloatingStatus(0x000c0000 or group.rounding)
                    fpu.updateMsaStatus(group.rounding)
                    for ((index, value) in case.inputs.withIndex()) {
                        val register = if (index == 2) 9 else 10 + index
                        if (vector) {
                            val packed = (0 until 64 step operation.inputBits)
                                .fold(0L) { result, shift -> result or (value shl shift) }
                            fpu.updateVector(register, VectorRegister(packed, packed))
                        } else {
                            fpu.updateLongRegister(register, value)
                        }
                    }
                    program.simulate(1)
                    val operands = case.inputs.joinToString { it.toULong().toString(16) }
                    val label = "$assembly, $group, $operands"
                    val status = if (vector) fpu.msaStatus else fpu.floatingStatus
                    assertEquals(case.flags, (status ushr 2) and 31, "$label flags")
                    assertEquals(case.flags, (status ushr 12) and 63, "$label causes")
                    val result = fpu.readVector(9)
                    if (vector) {
                        for (half in listOf(result.low, result.high)) {
                            for (shift in 0 until 64 step operation.resultBits) {
                                operation.assertResult(case, half ushr shift, label)
                            }
                        }
                    } else {
                        operation.assertResult(case, result.low, label)
                    }
                }
            }
        }
    }

    data class Case(val inputs: List<Long>, val result: Long, val flags: Int)

    data class Group(val operation: Operation, val mode: String, val cases: List<Case>) {
        val rounding = listOf("rnear_even", "rminMag", "rmax", "rmin").indexOf(mode)
        override fun toString() = "${operation.name} $mode"
    }

    class Operation(val name: String) {
        private val source = name.substringBefore('_')
        private val function = name.substringAfter('_')
        private val target = if (function.startsWith("to_")) function.removePrefix("to_") else source
        val inputBits = source.dropWhile(Char::isLetter).toInt()
        val resultBits = target.dropWhile(Char::isLetter).toInt()

        fun instruction(vector: Boolean): String? {
            val width = if (inputBits == 32) "s" else "d"
            val lanes = if (inputBits == 32) "w" else "d"
            if (function.startsWith("to_")) {
                return if (vector) vectorConversion(lanes) else scalarConversion(width)
            }
            val mnemonic = when (function) {
                "mulAdd" -> if (vector) "fmadd" else "maddf"
                "mulSub" -> if (vector) "fmsub" else "msubf"
                "roundToInt" -> if (vector) "frint" else "rint"
                else -> if (vector) "f$function" else function
            }
            val suffix = if (vector) lanes else width
            val operands = if (vector) $$"$w9,$w10" else $$"$f9,$f10"
            val second = when {
                function == "sqrt" || function == "roundToInt" -> ""
                vector -> $$",$w11"
                else -> $$",$f11"
            }
            return "$mnemonic.$suffix $operands$second"
        }

        private fun scalarConversion(width: String): String? {
            if (inputBits == 16 || resultBits == 16 || source.startsWith("u") || target.startsWith("u")) return null
            val sourceSuffix = if (source.startsWith("f")) width else if (inputBits == 32) "w" else "l"
            val targetSuffix = when {
                target == "f32" -> "s"
                target == "f64" -> "d"
                resultBits == 32 -> "w"
                else -> "l"
            }
            return $$"cvt.$$targetSuffix.$$sourceSuffix $f9,$f10"
        }

        private fun vectorConversion(lanes: String): String? = when {
            source.startsWith("f") && target.startsWith("f") -> when (source to target) {
                "f32" to "f16" -> $$"fexdo.h $w9,$w10,$w10"
                "f64" to "f32" -> $$"fexdo.w $w9,$w10,$w10"
                "f16" to "f32" -> $$"fexupr.w $w9,$w10"
                "f32" to "f64" -> $$"fexupr.d $w9,$w10"
                else -> error("Unexpected interchange conversion $name")
            }
            inputBits != resultBits -> null
            else -> {
                val signed = if (source.startsWith("u") || target.startsWith("u")) "u" else "s"
                val prefix = if (source.startsWith("f")) "ftint_" else "ffint_"
                prefix + $$"$$signed.$$lanes $w9,$w10"
            }
        }

        fun assertResult(case: Case, raw: Long, label: String) {
            val actual = raw and (-1L ushr (64 - resultBits))
            if (target.startsWith("f")) {
                val exponent = when (resultBits) {
                    16 -> 0x7c00L
                    32 -> 0x7f800000L
                    else -> 0x7ff0000000000000L
                }
                val quiet = (exponent and -exponent) ushr 1
                val fraction = (quiet shl 1) - 1
                if (case.result and exponent == exponent && case.result and fraction != 0L) {
                    // The exact MIPS NaN payload is covered by architecture-specific tests.
                    assertEquals(exponent or quiet, actual and (exponent or quiet), label)
                    return
                }
            } else if (case.flags and 16 != 0) {
                // TestFloat's invalid-integer result depends on its SoftFloat specialization.
                return
            }
            assertEquals(case.result, actual, label)
        }
    }

    companion object {
        @JvmStatic
        fun groups(): List<Group> = checkNotNull(javaClass.getResourceAsStream("/testfloat/expected.tsv"))
            .bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith('#') }.map { it.split('\t') }.toList()
                    .groupBy { it[0] to it[1] }
                    .map { (key, rows) ->
                        Group(Operation(key.first), key.second, rows.map { columns ->
                            Case(columns[2].split(',').map(::bits), bits(columns[3]), columns[4].toInt(16))
                        })
                    }
            }

        private fun bits(value: String) = value.toULong(16).toLong()
    }
}
