package edu.missouristate.mars.api

/**
 * Read and write access to the MIPS integer registers. Implementations support concurrent reads
 * from user interface threads while simulation is running.
 */
interface MarsRegisterFile {
    /** Read register 0–31, PC (32), HI (33), or LO (34). */
    fun getValue(register: Int): Int

    /** Write register 1–31, PC (32), HI (33), or LO (34). Register zero ignores writes. */
    fun setValue(register: Int, value: Int)

    fun getPC(): Int

    /** Reset registers to the initial values configured for this machine. */
    fun reset()
}
