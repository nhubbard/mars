package edu.missouristate.mars.assembler

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class TokenTypesTest {
    @Test
    fun numericClassificationPreservesSpecialValuesWhitespaceAndUnicodeDigits() {
        val examples = mapOf(
            "0" to TokenTypes.INTEGER_5,
            "+31" to TokenTypes.INTEGER_5,
            "32" to TokenTypes.INTEGER_16U,
            "-32" to TokenTypes.INTEGER_16,
            "0xffff" to TokenTypes.INTEGER_16U,
            "0xffffffff" to TokenTypes.INTEGER_16,
            "123456" to TokenTypes.INTEGER_32,
            "\u0661\u0662\u0663" to TokenTypes.INTEGER_16U,
            ".5" to TokenTypes.REAL_NUMBER,
            "1.5e2" to TokenTypes.REAL_NUMBER,
            "0x1.fp2" to TokenTypes.REAL_NUMBER,
            "1f" to TokenTypes.REAL_NUMBER,
            "-Infinity" to TokenTypes.REAL_NUMBER,
            "+NaN" to TokenTypes.REAL_NUMBER,
            "Infinity" to TokenTypes.REAL_NUMBER,
            "NaN" to TokenTypes.REAL_NUMBER,
            " \t1.5 " to TokenTypes.REAL_NUMBER,
            "\u0000+Infinity\u0000" to TokenTypes.REAL_NUMBER,
            "NAN" to TokenTypes.IDENTIFIER,
            "next" to TokenTypes.IDENTIFIER,
            "Input" to TokenTypes.IDENTIFIER,
            "value" to TokenTypes.IDENTIFIER,
            "addiu" to TokenTypes.OPERATOR,
            ".word" to TokenTypes.DIRECTIVE,
            "12xyz" to TokenTypes.ERROR,
        )
        for (target in MarsTarget.entries) {
            val catalog = InstructionDefaults.forTarget(target)
            for ((source, expected) in examples) {
                assertEquals(expected, TokenTypes.matchTokenType(source, catalog), "$target $source")
            }
        }
    }
}
