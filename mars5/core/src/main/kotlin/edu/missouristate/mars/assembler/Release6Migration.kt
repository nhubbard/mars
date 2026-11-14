package edu.missouristate.mars.assembler

/** Source migration guidance shared by the assembler and the editor. */
fun release6Migration(mnemonic: String): String? {
    val name = mnemonic.lowercase()
    return when (name) {
        "lwl", "lwr" -> "Use lw at the first byte of a complete unaligned word, or explicitly merge the bytes you need."
        "swl", "swr" -> "Use sw at the first byte of a complete unaligned word, or explicitly store the bytes you need."
        "ldl", "ldr" -> "Use ld for a complete unaligned doubleword, or explicitly merge the bytes you need."
        "sdl", "sdr" -> "Use sd for a complete unaligned doubleword, or explicitly store the bytes you need."
        "mfhi", "mflo", "mthi", "mtlo", "mult", "multu", "dmult", "dmultu", "madd", "maddu", "msub", "msubu" ->
            "Use explicit destination registers with mul, muh, div, or mod and their unsigned or doubleword forms. Rewrite any HI/LO accumulation explicitly."
        "bc1f", "bc1t" -> "Use a cmp instruction to write a truth mask, then test it with bc1eqz or bc1nez."
        "bc1fl", "bc1tl", "beql", "bnel", "bgezl", "bgtzl", "blezl", "bltzl", "bgezall", "bltzall" ->
            "Move the annulled delay-slot instruction onto the taken path before replacing the branch."
        "bgezal", "bltzal" ->
            "Use bal for an unconditional call, or review the link and slot behavior before switching to a compact conditional call."
        "addi", "daddi" ->
            "Use add or dadd with a register holding the immediate to retain overflow trapping. Use addiu or daddiu when wrapping is intended."
        "lwxc1", "ldxc1", "luxc1", "swxc1", "sdxc1", "suxc1", "prefx" ->
            "Compute the effective address with daddu, then use the corresponding base-and-offset load, store, or prefetch."
        "movf", "movt", "movn", "movz", "movf.s", "movf.d", "movt.s", "movt.d", "movn.s", "movn.d", "movz.s", "movz.d" ->
            "Rewrite the condition as an explicit branch or compare and select. Preserve the destination on the path where the move is skipped."
        "madd.s", "madd.d", "msub.s", "msub.d", "nmadd.s", "nmadd.d", "nmsub.s", "nmsub.d" ->
            "Use explicit floating-point arithmetic. Review rounding before replacing the operation with a fused multiply-add or subtract."
        "jalx" -> "Use a call within the MARS64 instruction mode. MARS64 has no alternate instruction mode to switch to."
        "bc2f", "bc2t", "bc2fl", "bc2tl" ->
            "Rewrite the test with data available in the simulator. These branches depend on a platform-specific coprocessor."
        "lwle", "lwre", "swle", "swre" ->
            "Use ordinary memory operations for user data. The original instruction also required privileged Enhanced Virtual Addressing."
        "cvt.s.pl", "cvt.s.pu" -> "Keep the two values in separate scalar registers, or use MSA lanes."
        in pairedSingleInstructions -> "Use two scalar single-precision operations or rewrite the operation using MSA lanes."
        else -> {
            val parts = name.split('.')
            if (parts.size != 3 || parts[0] != "c" || parts[1] !in floatingConditions) return null
            when (parts[2]) {
                "s", "d" -> "Use the corresponding cmp operation to write a truth mask to an FPR, then use bc1eqz or bc1nez."
                "ps" -> "Compare each value separately, or use MSA lane comparisons."
                else -> null
            }
        }
    }
}

private val floatingConditions = setOf(
    "f", "un", "eq", "ueq", "olt", "ult", "ole", "ule", "sf", "ngle", "seq", "ngl", "lt", "nge", "le", "ngt",
)

private val pairedSingleInstructions = setOf(
    "abs.ps", "add.ps", "alnv.ps", "cvt.ps.s", "madd.ps", "mov.ps", "movf.ps", "movn.ps", "movt.ps", "movz.ps", "msub.ps",
    "mul.ps", "neg.ps", "nmadd.ps", "nmsub.ps", "pll.ps", "plu.ps", "pul.ps", "puu.ps", "sub.ps",
)
