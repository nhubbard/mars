package edu.missouristate.mars.mips.hardware

/**
 * Models the memory configuration for the simulated MIPS machine.
 * "Configuration" refers to the starting memory addresses for the various memory segments.
 * The default configuration is based on SPIM. Starting with MARS 3.7, the configuration can be changed.
 */
class MemoryConfiguration(
    @JvmField val configurationIdentifier: String,
    @JvmField val configurationName: String,
    @JvmField val configurationItemNames: Array<String>,
    @JvmField val configurationItemValues: IntArray
) {
    val textBaseAddress: Int get() = configurationItemValues[0]
    val dataSegmentBaseAddress: Int get() = configurationItemValues[1]
    val externBaseAddress: Int get() = configurationItemValues[2]
    val globalPointer: Int get() = configurationItemValues[3]
    val dataBaseAddress: Int get() = configurationItemValues[4]
    val heapBaseAddress: Int get() = configurationItemValues[5]
    val stackPointer: Int get() = configurationItemValues[6]
    val stackBaseAddress: Int get() = configurationItemValues[7]
    val userHighAddress: Int get() = configurationItemValues[8]
    val kernelBaseAddress: Int get() = configurationItemValues[9]
    val kernelTextBaseAddress: Int get() = configurationItemValues[10]
    val exceptionHandlerAddress: Int get() = configurationItemValues[11]
    val kernelDataBaseAddress: Int get() = configurationItemValues[12]
    val memoryMapBaseAddress: Int get() = configurationItemValues[13]
    val kernelHighAddress: Int get() = configurationItemValues[14]
    val dataSegmentLimitAddress: Int get() = configurationItemValues[15]
    val textLimitAddress: Int get() = configurationItemValues[16]
    val kernelDataSegmentLimitAddress: Int get() = configurationItemValues[17]
    val kernelTextLimitAddress: Int get() = configurationItemValues[18]
    val stackLimitAddress: Int get() = configurationItemValues[19]
    val memoryMapLimitAddress: Int get() = configurationItemValues[20]
}