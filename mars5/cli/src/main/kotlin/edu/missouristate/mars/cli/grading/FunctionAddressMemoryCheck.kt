package edu.missouristate.mars.cli.grading

/** Eight native-endian bytes storing an assembled data address, independent of relocation. */
data class FunctionAddressMemoryCheck(val at: FunctionDataAddress, val value: FunctionDataAddress)
