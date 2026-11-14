package edu.missouristate.mars.assembler

/** Release 6 forms whose behavior depends on hardware the simulator does not model. */
internal fun release6HardwareRequirement(mnemonic: String): String? = when (mnemonic.lowercase()) {
    "bc2eqz", "bc2nez", "cfc2", "cop2", "ctc2", "dmfc2", "dmtc2", "ldc2", "lwc2", "mfc2", "mfhc2", "mtc2",
    "mthc2", "sdc2", "swc2" -> "a platform-specific coprocessor 2"
    "lbe", "lbue", "lhe", "lhue", "lle", "llwpe", "lwe", "prefe", "sbe", "sce", "scwpe", "she", "swe" ->
        "privileged Enhanced Virtual Addressing"
    "cachee" -> "privileged Enhanced Virtual Addressing and cache state"
    "ginvi", "ginvt", "tlbinv", "tlbinvf", "tlbp", "tlbr", "tlbwi", "tlbwr" -> "privileged address translation"
    "rdpgpr", "wrpgpr" -> "shadow register sets"
    "mfhc0", "mthc0" -> "the optional CP0 Move High (MVH) feature"
    "wait" -> "hardware interrupt and power management"
    "cache" -> "privileged cache state"
    "deret", "sdbbp" -> "EJTAG Debug Mode"
    else -> null
}
