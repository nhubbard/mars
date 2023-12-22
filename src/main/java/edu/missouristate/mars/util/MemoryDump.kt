package edu.missouristate.mars.util

import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory

object MemoryDump {
    /**
     * Get the names of segments available for memory dump.
     *
     * @return array of Strings, each string is segment name (e.g. ".text", ".data")
     */
    @JvmField
    val segmentNames: Array<String> = arrayOf(".text", ".data")
    private val baseAddresses = IntArray(2)
    private val limitAddresses = IntArray(2)

    /**
     * Return array with segment address bounds for specified segment.
     *
     * @param segment String with segment name (initially ".text" and ".data")
     * @return array of two Integer, the base and limit address for that segment.  Null if parameter
     * name does not match a known segment name.
     */
    @JvmStatic
    fun getSegmentBounds(segment: String): Array<Int>? {
        for (i in segmentNames.indices) {
            if (segmentNames[i] == segment) {
                val bounds = Array(2) { 0 }
                bounds[0] = getBaseAddresses()[i]
                bounds[1] = getLimitAddresses()[i]
                return bounds
            }
        }
        return null
    }

    /**
     * Get the MIPS memory base address(es) of the specified segment name(s).
     * If an invalid segment name is provided, will throw NullPointerException, so
     * I recommend getting segment names from getSegmentNames().
     *
     * @return Array of int containing corresponding base addresses.
     */
    @JvmStatic
    fun getBaseAddresses(): IntArray {
        baseAddresses[0] = Memory.textBaseAddress
        baseAddresses[1] = Memory.dataBaseAddress
        return baseAddresses
    }

    /**
     * Get the MIPS memory limit address(es) of the specified segment name(s).
     * If an invalid segment name is provided, will throw NullPointerException, so
     * I recommend getting segment names from getSegmentNames().
     *
     * @return Array of int containing corresponding limit addresses.
     */
    @JvmStatic
    fun getLimitAddresses(): IntArray {
        limitAddresses[0] = Memory.textLimitAddress
        limitAddresses[1] = Memory.dataSegmentLimitAddress
        return limitAddresses
    }

    /**
     * Look for the first "null" memory value in an address range.
     * For text segment (binary code), this represents a word that does not contain an instruction.
     * Normally use this to find the end of the program.
     * For data segment, this represents the first block of simulated memory (block length
     * currently 4K words) that has not been referenced by an assembled/executing program.
     *
     * @param baseAddress  lowest MIPS address to be searched; the starting point
     * @param limitAddress highest MIPS address to be searched
     * @return lowest address within the specified range that contains "null" value as described above.
     * @throws AddressErrorException if the base address is not on a word boundary
     */
    @Throws(AddressErrorException::class)
    @JvmStatic
    fun getAddressOfFirstNull(baseAddress: Int, limitAddress: Int): Int {
        var address = baseAddress
        while (address < limitAddress) {
            if (Globals.memory.getRawWordOrNull(address) == null) break
            address += Memory.WORD_LENGTH_BYTES
        }
        return address
    }
}