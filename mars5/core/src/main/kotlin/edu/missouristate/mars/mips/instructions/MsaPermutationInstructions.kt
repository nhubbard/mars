package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.I_BRANCH_FORMAT
import edu.missouristate.mars.mips.instructions.BasicInstructionFormat.R_FORMAT

internal fun msaPermutationInstructions(): List<BasicInstruction> = buildList {
  for ((format, suffix) in "bhwd".withIndex()) {
    val bits = Byte.SIZE_BITS shl format
    for (operation in Permutation.entries) add(permute(operation, format, suffix, bits))
    for (splat in listOf(false, true)) {
      add(indexed(format, suffix, bits, immediate = false, splat))
      add(indexed(format, suffix, bits, immediate = true, splat))
    }
    if (format < 3) add(shuffleSet(format, suffix, bits))
  }
}

internal fun msaBranchInstructions(): List<BasicInstruction> = buildList {
  for ((format, suffix) in "bhwdv".withIndex()) {
    add(branchOnVector(format, suffix, nonzero = false))
    add(branchOnVector(format, suffix, nonzero = true))
  }
}

private fun branchOnVector(format: Int, suffix: Char, nonzero: Boolean): BasicInstruction {
  val whole = suffix == 'v'
  val opcode = (if (whole) 11 else 24 + format) + if (nonzero) 4 else 0
  val field = opcode.toString(2).padStart(5, '0')
  val bits = Byte.SIZE_BITS shl format
  val name = if (nonzero) "bnz" else "bz"
  return object :
      BasicInstruction(
          $$"$$name.$$suffix $w1,label",
          "Branch on zero or nonzero vector data",
          I_BRANCH_FORMAT,
          "010001 $field fffff ssssssssssssssss",
          { statement, context ->
            val (source, displacement) = statement.operands
            val vector = context.coprocessor1.readVector(source)
            val zero =
                if (whole) vector.low == 0L && vector.high == 0L
                else (0 until 128 / bits).any { vector.lane(bits, it) == 0L }
            context.release6DelayedBranch(displacement, zero != nonzero)
          },
      ) {
    override fun encodeOperands(values: IntArray): IntArray {
      require(values[1] in Short.MIN_VALUE..Short.MAX_VALUE) {
        "Branch destination is outside its signed displacement range"
      }
      return values
    }

    override fun decodeOperands(values: IntArray) {
      values[1] = values[1].toShort().toInt()
    }
  }
}

private enum class Permutation(
    val mnemonic: String,
    val opcode: Int,
    val function: String = "010100",
) {
  PACK_EVEN("pckev", 2),
  PACK_ODD("pckod", 3),
  INTERLEAVE_LEFT("ilvl", 4),
  INTERLEAVE_RIGHT("ilvr", 5),
  INTERLEAVE_EVEN("ilvev", 6),
  INTERLEAVE_ODD("ilvod", 7),
  SHUFFLE("vshf", 0, "010101"),
}

private fun permute(
    operation: Permutation,
    format: Int,
    suffix: Char,
    bits: Int,
): BasicInstruction {
  val field = (operation.opcode * 4 + format).toString(2).padStart(5, '0')
  val lanes = 128 / bits
  val half = lanes / 2
  return BasicInstruction(
      $$"$${operation.mnemonic}.$$suffix $w1,$w2,$w3",
      "Rearrange vector elements",
      R_FORMAT,
      "011110 $field ttttt sssss fffff ${operation.function}",
  ) { statement, context ->
    val (destination, source, target) = statement.operands
    val fpu = context.coprocessor1
    val left = fpu.readVector(source)
    val right = fpu.readVector(target)
    val control = if (operation == Permutation.SHUFFLE) fpu.readVector(destination) else null
    val result =
        packLanes(bits) { index ->
          val interleaved = if (index % 2 == 0) right else left
          val packed = if (index < half) right else left
          when (operation) {
            Permutation.PACK_EVEN -> packed.lane(bits, (index % half) * 2)
            Permutation.PACK_ODD -> packed.lane(bits, (index % half) * 2 + 1)
            Permutation.INTERLEAVE_LEFT -> interleaved.lane(bits, index / 2 + half)
            Permutation.INTERLEAVE_RIGHT -> interleaved.lane(bits, index / 2)
            Permutation.INTERLEAVE_EVEN -> interleaved.lane(bits, index and -2)
            Permutation.INTERLEAVE_ODD -> interleaved.lane(bits, index or 1)
            Permutation.SHUFFLE -> {
              val selector = checkNotNull(control).lane(bits, index).toInt()
              val selected = selector and (lanes * 2 - 1)
              if (selector and 0xc0 != 0) 0L
              else if (selected < lanes) right.lane(bits, selected)
              else left.lane(bits, selected - lanes)
            }
          }
        }
    context.updateVectorRegister(destination, result)
  }
}

private fun indexed(
    format: Int,
    suffix: Char,
    bits: Int,
    immediate: Boolean,
    splat: Boolean,
): BasicInstruction {
  val name = (if (splat) "splat" else "sld") + if (immediate) "i" else ""
  val laneCount = 128 / bits
  val opcode =
      if (immediate) {
        (if (splat) "0001" else "0000") + "1".repeat(format) + "00" + "t".repeat(4 - format)
      } else {
        (if (splat) "001" else "000") + format.toString(2).padStart(2, '0') + "ttttt"
      }
  val function = if (immediate) "011001" else "010100"
  val selector = if (immediate) "0" else $$"$t1"
  return object :
      BasicInstruction(
          $$"$$name.$$suffix $w1,$w2[$$selector]",
          if (splat) "Broadcast a selected vector element" else "Slide each row of vector bytes",
          R_FORMAT,
          "011110 $opcode sssss fffff $function",
          { statement, context ->
            val (destination, source, operand) = statement.operands
            val fpu = context.coprocessor1
            val input = fpu.readVector(source)
            val index =
                if (immediate) operand
                else context.registerFile.getValue(operand) and (laneCount - 1)
            val result =
                if (splat) {
                  val value = input.lane(bits, index)
                  packLanes(bits) { value }
                } else {
                  val previous = fpu.readVector(destination)
                  packLanes(Byte.SIZE_BITS) { output ->
                    val row = output / laneCount * laneCount
                    val column = output % laneCount + index
                    if (column < laneCount) input.lane(Byte.SIZE_BITS, row + column)
                    else previous.lane(Byte.SIZE_BITS, row + column - laneCount)
                  }
                }
            context.updateVectorRegister(destination, result)
          },
      ) {
    override fun encodeOperands(values: IntArray): IntArray {
      require(!immediate || values[2] in 0 until laneCount) {
        "Vector lane must be between 0 and ${laneCount - 1}"
      }
      return values
    }
  }
}

private fun shuffleSet(format: Int, suffix: Char, bits: Int) =
    object :
        BasicInstruction(
            $$"shf.$$suffix $w1,$w2,255",
            "Shuffle each four-element group with a shared immediate",
            R_FORMAT,
            "011110 ${format.toString(2).padStart(2, '0')} tttttttt sssss fffff 000010",
            { statement, context ->
              val (destination, source, control) = statement.operands
              val input = context.coprocessor1.readVector(source)
              context.updateVectorRegister(
                  destination,
                  packLanes(bits) { index ->
                    val selector = (control ushr ((index and 3) * 2)) and 3
                    input.lane(bits, (index and -4) + selector)
                  },
              )
            },
        ) {
      override fun encodeOperands(values: IntArray): IntArray {
        require(values[2] in 0..255) { "Shuffle control must be between 0 and 255" }
        return values
      }
    }

private fun VectorRegister.lane(bits: Int, index: Int): Long {
  val offset = index * bits
  return ((if (offset < 64) low else high) ushr (offset and 63)) and (-1L ushr (64 - bits))
}

private inline fun packLanes(bits: Int, lane: (Int) -> Long): VectorRegister {
  var low = 0L
  var high = 0L
  val half = 64 / bits
  for (index in 0 until half) {
    val shift = index * bits
    low = low or (lane(index) shl shift)
    high = high or (lane(index + half) shl shift)
  }
  return VectorRegister(low, high)
}
