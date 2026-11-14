package edu.missouristate.mars.assembler

internal fun release6Migration(mnemonic: String): String? = when (mnemonic.lowercase()) {
    "lwl", "lwr" -> "Use lw at the first byte of a complete unaligned word, or explicitly merge the bytes you need."
    "swl", "swr" -> "Use sw at the first byte of a complete unaligned word, or explicitly store the bytes you need."
    "mfhi", "mflo", "mthi", "mtlo", "mult", "multu", "madd", "maddu", "msub", "msubu" ->
        "Use explicit destination registers with mul, muh, div, or mod and their unsigned forms. Rewrite any HI/LO accumulation explicitly."
    "bc1f", "bc1t" -> "Use a cmp instruction to write a truth mask, then test it with bc1eqz or bc1nez."
    "bgezal", "bltzal" -> "Use bal for an unconditional call, or review the link and slot behavior before switching to a compact conditional call."
    else -> null
}
