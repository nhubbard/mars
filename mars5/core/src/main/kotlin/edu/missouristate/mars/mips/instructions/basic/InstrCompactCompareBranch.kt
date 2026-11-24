package edu.missouristate.mars.mips.instructions.basic

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT

internal class InstrCompactCompareBranch(private val form: CompactComparison) :
    BasicInstruction(
        "${form.mnemonic} ${form.registers.example},label",
        form.description,
        I_BRANCH_FORMAT,
        "${form.opcode} ${form.registers.mask}",
        { statement, context ->
          val operands = statement.operands
          context.checkCompactTransferSlot(statement)
          val pair = form.registers.isPair
          val left = context.readIntegerOperand(operands[0])
          val right = if (pair) context.readIntegerOperand(operands[1]) else 0L
          val displacement = operands[if (pair) 2 else 1]
          val taken = form.test(left, right)
          if (form.link) {
            val returnAddress = context.registerFile.programCounterBits
            if (taken) context.link(31, returnAddress)
            else context.updateLongRegister(31, returnAddress)
          }
          if (taken) {
            context.compactTransfer(
                statement,
                context.registerFile.programCounterBits + (displacement shl 2),
                branch = true,
            )
          }
        },
    ) {
  override val isCompactConditionalBranch: Boolean
    get() = true

  override fun encodeOperands(values: IntArray): IntArray {
    val left = values[0]
    val pair = form.registers.isPair
    val right = if (pair) values[1] else 0
    require(
        form.registers == RegisterFields.OVERFLOW_PAIR ||
            left != 0 && (!pair || right != 0 && left != right),
    ) {
      "Use distinct nonzero registers for a two-register compact branch, or one nonzero register for a compact zero comparison"
    }
    require(!form.link || left != 31) { "$name source register must not be the link register" }
    require(values[if (pair) 2 else 1] in Short.MIN_VALUE..Short.MAX_VALUE) {
      "Compact branch destination is outside its signed displacement range"
    }
    if (
        form.registers == RegisterFields.ORDERED_PAIR && left > right ||
            form.registers == RegisterFields.OVERFLOW_PAIR && left < right
    ) {
      return intArrayOf(right, left, values[2])
    }
    if (form.registers == RegisterFields.REPEATED)
        return intArrayOf((left shl 5) or left, values[1])
    return values
  }

  override fun decodeOperands(values: IntArray) {
    if (form.registers == RegisterFields.REPEATED) values[0] = values[0] and 31
    if (!form.registers.isPair) values[1] = values[1].toShort().toInt()
  }

  override fun acceptsEncoding(word: Int): Boolean {
    val rs = (word ushr 21) and 31
    val rt = (word ushr 16) and 31
    return when (form.registers) {
      RegisterFields.ZERO_LEFT -> rs == 0 && rt != 0
      RegisterFields.REPEATED -> rs != 0 && rs == rt
      RegisterFields.PAIR -> rs != 0 && rt != 0 && rs != rt
      RegisterFields.ORDERED_PAIR -> rs != 0 && rs < rt
      RegisterFields.OVERFLOW_PAIR -> rs >= rt
    }
  }
}

internal enum class RegisterFields(val mask: String, val isPair: Boolean = false) {
  ZERO_LEFT("00000 fffff ssssssssssssssss"),
  REPEATED("fffff fffff ssssssssssssssss"),
  PAIR("fffff sssss tttttttttttttttt", true),
  ORDERED_PAIR("fffff sssss tttttttttttttttt", true),
  OVERFLOW_PAIR("fffff sssss tttttttttttttttt", true);

  val example: String
    get() = if (isPair) $$"$t1,$t2" else $$"$t1"
}

internal enum class CompactComparison(
    val mnemonic: String,
    val opcode: String,
    val registers: RegisterFields,
    val description: String,
    val link: Boolean = false,
    val wideOnly: Boolean = false,
    val test: (Long, Long) -> Boolean,
) {
  LEZ(
      "blezc",
      "010110",
      RegisterFields.ZERO_LEFT,
      "Branch immediately if the register is at most zero",
      test = { a, _ -> a <= 0 },
  ),
  GEZ(
      "bgezc",
      "010110",
      RegisterFields.REPEATED,
      "Branch immediately if the register is at least zero",
      test = { a, _ -> a >= 0 },
  ),
  GTZ(
      "bgtzc",
      "010111",
      RegisterFields.ZERO_LEFT,
      "Branch immediately if the register is positive",
      test = { a, _ -> a > 0 },
  ),
  LTZ(
      "bltzc",
      "010111",
      RegisterFields.REPEATED,
      "Branch immediately if the register is negative",
      test = { a, _ -> a < 0 },
  ),
  GE(
      "bgec",
      "010110",
      RegisterFields.PAIR,
      "Branch immediately if the first signed register is at least the second",
      test = { a, b -> a >= b },
  ),
  LT(
      "bltc",
      "010111",
      RegisterFields.PAIR,
      "Branch immediately if the first signed register is less than the second",
      test = { a, b -> a < b },
  ),
  GEU(
      "bgeuc",
      "000110",
      RegisterFields.PAIR,
      "Branch immediately if the first unsigned register is at least the second",
      test = { a, b -> a.toULong() >= b.toULong() },
  ),
  LTU(
      "bltuc",
      "000111",
      RegisterFields.PAIR,
      "Branch immediately if the first unsigned register is less than the second",
      test = { a, b -> a.toULong() < b.toULong() },
  ),
  NE(
      "bnec",
      "011000",
      RegisterFields.ORDERED_PAIR,
      "Branch immediately if two registers are unequal",
      test = { a, b -> a != b },
  ),
  EQ(
      "beqc",
      "001000",
      RegisterFields.ORDERED_PAIR,
      "Branch immediately if two registers are equal",
      wideOnly = true,
      test = { a, b -> a == b },
  ),
  LEZ_LINK(
      "blezalc",
      "000110",
      RegisterFields.ZERO_LEFT,
      "Link and branch if the register is at most zero",
      link = true,
      wideOnly = true,
      test = { a, _ -> a <= 0 },
  ),
  GEZ_LINK(
      "bgezalc",
      "000110",
      RegisterFields.REPEATED,
      "Link and branch if the register is at least zero",
      link = true,
      wideOnly = true,
      test = { a, _ -> a >= 0 },
  ),
  GTZ_LINK(
      "bgtzalc",
      "000111",
      RegisterFields.ZERO_LEFT,
      "Link and branch if the register is positive",
      link = true,
      wideOnly = true,
      test = { a, _ -> a > 0 },
  ),
  LTZ_LINK(
      "bltzalc",
      "000111",
      RegisterFields.REPEATED,
      "Link and branch if the register is negative",
      link = true,
      wideOnly = true,
      test = { a, _ -> a < 0 },
  ),
  NEZ_LINK(
      "bnezalc",
      "011000",
      RegisterFields.ZERO_LEFT,
      "Link and branch if the register is nonzero",
      link = true,
      wideOnly = true,
      test = { a, _ -> a != 0L },
  ),
  EZ_LINK(
      "beqzalc",
      "001000",
      RegisterFields.ZERO_LEFT,
      "Link and branch if the register is zero",
      link = true,
      wideOnly = true,
      test = { a, _ -> a == 0L },
  ),
  OVERFLOW(
      "bovc",
      "001000",
      RegisterFields.OVERFLOW_PAIR,
      "Branch if either operand or their sum overflows a signed word",
      wideOnly = true,
      test = ::wordAddOverflows,
  ),
  NO_OVERFLOW(
      "bnvc",
      "011000",
      RegisterFields.OVERFLOW_PAIR,
      "Branch if both operands and their sum fit a signed word",
      wideOnly = true,
      test = { a, b -> !wordAddOverflows(a, b) },
  ),
}

private fun wordAddOverflows(a: Long, b: Long): Boolean =
    a != a.toInt().toLong() ||
        b != b.toInt().toLong() ||
        a + b !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
