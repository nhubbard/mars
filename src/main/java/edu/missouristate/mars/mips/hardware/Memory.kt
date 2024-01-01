/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("DEPRECATION", "NAME_SHADOWING")

package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.*
import edu.missouristate.mars.Globals.settings
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultDataBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultDataSegmentBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultExceptionHandlerAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultExternBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultGlobalPointer
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultHeapBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultKernelBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultKernelDataBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultKernelHighAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultKernelTextBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultMemoryMapBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultStackBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultStackPointer
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultTextBaseAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.defaultUserHighAddress
import edu.missouristate.mars.mips.hardware.MemoryConfigurations.getCurrentConfiguration
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.simulator.Exceptions
import edu.missouristate.mars.util.Binary
import java.nio.ByteOrder
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Represents MIPS memory. Different segments are represented by different data structures.
 */
class Memory private constructor() : Observable() {
    companion object {
        /** base address for (user) text segment: 0x00400000 */
        @JvmStatic
        var textBaseAddress: Int = defaultTextBaseAddress

        /** base address for (user) data segment: 0x10000000 */
        @JvmStatic
        var dataSegmentBaseAddress: Int = defaultDataSegmentBaseAddress

        /** base address for .extern directive: 0x10000000 */
        @JvmStatic
        var externBaseAddress: Int = defaultExternBaseAddress

        /** base address for storing globals */
        @JvmStatic
        var globalPointer: Int = defaultGlobalPointer

        /** base address for storage of non-global static data in data segment: 0x10010000 (from SPIM) */
        @JvmStatic
        var dataBaseAddress: Int = defaultDataBaseAddress

        /** base address for heap: 0x10040000 (I think from SPIM not MIPS) */
        @JvmStatic
        var heapBaseAddress: Int = defaultHeapBaseAddress

        /** starting address for stack: 0x7fffeffc (this is from SPIM not MIPS) */
        @JvmStatic
        var stackPointer: Int = defaultStackPointer

        /** base address for stack: 0x7ffffffc (this is mine - the start of the highest word below kernel space) */
        @JvmStatic
        var stackBaseAddress: Int = defaultStackBaseAddress

        /** highest address accessible in user (not kernel) mode. */
        @JvmStatic
        var userHighAddress: Int = defaultUserHighAddress

        /** kernel boundary. Only OS can access this or higher address */
        @JvmStatic
        var kernelBaseAddress: Int = defaultKernelBaseAddress

        /** base address for kernel text segment: 0x80000000 */
        @JvmStatic
        var kernelTextBaseAddress: Int = defaultKernelTextBaseAddress

        /** starting address for exception handlers: 0x80000180 */
        @JvmStatic
        var exceptionHandlerAddress: Int = defaultExceptionHandlerAddress

        /** base address for kernel data segment: 0x90000000 */
        @JvmStatic
        var kernelDataBaseAddress: Int = defaultKernelDataBaseAddress

        /** starting address for memory mapped I/O: 0xffff0000 (-65536) */
        @JvmStatic
        var memoryMapBaseAddress: Int = defaultMemoryMapBaseAddress

        /** highest address accessible in kernel mode. */
        @JvmStatic
        var kernelHighAddress: Int = defaultKernelHighAddress

        /**
         * MIPS word length in bytes.
         */
        // NOTE: Much of the code is hardwired for 4 byte words. Refactoring this is low priority.
        const val WORD_LENGTH_BYTES = 4

        /**
         * Current setting for endianness (default LITTLE_ENDIAN)
         */
        private var byteOrder = ByteOrder.LITTLE_ENDIAN

        @JvmStatic
        var heapAddress: Int = 0

        /**
         * Memory will maintain a collection of observables. Each one is associated
         * with a specific memory address or address range, and each will have at least
         * one observer registered with it. When memory access is made, make sure only
         * observables associated with that address send notices to their observers.
         * This assures that observers are not bombarded with notices from memory
         * addresses they do not care about.
         *
         * Would like a tree-like implementation, but that is complicated by this fact:
         * key for insertion into the tree would be based on Comparable using both low
         * and high end of address range, but retrieval from the tree has to be based
         * on target address being ANYWHERE IN THE RANGE (not an exact key match).
         */
        @JvmStatic
        var observables: Vector<MemoryObservable> = getNewMemoryObserversCollection()

        /**
         * The data segment is allocated in blocks of 1024 ints (4096 bytes). Each block is
         * referenced by a "block table" entry, and the table has 1024 entries. The capacity
         * is thus 1024 entries * 4096 bytes = 4 MB. Should be enough to cover most
         * programs!! Beyond that, it would go to an "indirect" block (similar to Unix i-nodes),
         * which is not implemented.

         * Although this scheme is an array of arrays, it is relatively space-efficient since
         * only the table is created initially. A 4096-byte block is not allocated until a value
         * is written to an address within it. Thus, most small programs will use only 8K bytes
         * of space (the table plus one block). The index into both arrays is easily computed
         * from the address; access time is constant.

         * SPIM stores statically allocated data (following first .data directive) starting
         * at location 0x10010000. This is the first Data Segment word beyond the reach of $gp
         * used in conjunction with signed 16-bit immediate offset. $gp has value 0x10008000
         * and with the signed 16-bit offset can reach from 0x10008000 - 0xFFFF = 0x10000000
         * (Data Segment base) to 0x10008000 + 0x7FFF = 0x1000FFFF (the byte preceding 0x10010000).

         * Using my scheme, 0x10010000 falls at the beginning of the 17'th block -- table entry 16.
         * SPIM uses a heap base address of 0x10040000 which is not part of the MIPS specification.
         * (I don't have a reference for that offhand...) Using my scheme, 0x10040000 falls at
         * the start of the 65'th block -- table entry 64. That leaves (1024-64) * 4096 = 3,932,160
         * bytes of space available without going indirect.
         */
        private const val BLOCK_LENGTH_WORDS = 1024 // allocated block size 1024 ints == 4K bytes

        private const val BLOCK_TABLE_LENGTH = 1024 // Each entry of the table points to a block.

        @JvmStatic
        private lateinit var dataBlockTable: Array<IntArray?>

        @JvmStatic
        private lateinit var kernelDataBlockTable: Array<IntArray?>

        /**
         * The stack is modeled similarly to the data segment. It cannot share the same
         * data structure because the stack base address is very large. To store it in the
         * same data structure would require implementation of indirect blocks, which has not
         * been realized. So the stack gets its own table of blocks using the same dimensions
         * and allocation scheme used for data segment.

         * The other major difference is the stack grows DOWNWARD from its base address, not
         * upward. I.e., the stack base is the largest stack address. This turns the whole
         * scheme for translating memory address to block-offset on its head!  The simplest
         * solution is to calculate relative address (offset from base) by subtracting the
         * desired address from the stack base address (rather than subtracting base address
         * from the desired address). Thus, as the address gets smaller, the offset gets larger.
         * Everything else works the same, so it shares some private helper methods with
         * data segment algorithms.
         */
        @JvmStatic
        private lateinit var stackBlockTable: Array<IntArray?>

        /**
         * Memory mapped I/O is simulated with a separate table using the same structure and
         * logic as data segment. Memory is allocated in 4K byte blocks. But since MMIO
         * address range is limited to 0xffff0000 to 0xfffffffc, there is only 64K bytes
         * total. Thus, there will be a maximum of 16 blocks, and I never suspect more than
         * one since only the first few addresses are typically used. The only exception
         * may be a rogue program generating such addresses in a loop. Note that Java interprets the
         * MMIO addresses as negative numbers since it does not
         * have unsigned types. As long as the absolute address is correctly translated
         * into a table offset, this is of no concern.
         */
        private const val MMIO_TABLE_LENGTH = 16 // Each entry of the table points to a 4K block.

        @JvmStatic
        private lateinit var memoryMapBlockTable: Array<IntArray?>


        /**
         * I use a similar scheme for storing instructions. MIPS text segment ranges from
         * 0x00400000 all the way to data segment (0x10000000) a range of about 250 MB!  So
         * I'll provide a table of blocks with similar capacity. This differs from the data segment
         * somewhat in that the block entries do not contain int's, but instead contain
         * references to ProgramStatement objects.
         */
        private const val TEXT_BLOCK_LENGTH_WORDS = 1024 // allocated block size 1024 ints == 4K bytes

        private const val TEXT_BLOCK_TABLE_LENGTH = 1024 // Each entry of the table points to a block.

        @JvmStatic
        private lateinit var textBlockTable: Array<Array<ProgramStatement?>?>

        @JvmStatic
        private lateinit var kernelTextBlockTable: Array<Array<ProgramStatement?>?>


        /**
         * Set "top" address boundary to go with each "base" address. This determines permissible
         * address range for user program. Currently, the limit is 4MB, or 1024 * 1024 * four bytes based
         * on the table structures described above (except memory-mapped IO, limited to 64KB by range).
         */
        @JvmStatic
        var dataSegmentLimitAddress =
            dataSegmentBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES

        @JvmStatic
        var textLimitAddress = textBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES

        @JvmStatic
        var kernelDataSegmentLimitAddress =
            kernelDataBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES

        @JvmStatic
        var kernelTextLimitAddress =
            kernelTextBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES

        @JvmStatic
        var stackLimitAddress = stackBaseAddress - BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES

        @JvmStatic
        var memoryMapLimitAddress = memoryMapBaseAddress + BLOCK_LENGTH_WORDS * MMIO_TABLE_LENGTH * WORD_LENGTH_BYTES

        /**
         * The globally unique KMemory instance.
         */
        @JvmStatic
        val instance = Memory()

        /**
         * Set the current memory configuration. Configuration is a collection of memory segment addresses.
         * It can be modified starting with MARS 3.7.
         */
        @JvmStatic
        fun setConfiguration() {
            textBaseAddress = getCurrentConfiguration().textBaseAddress //0x00400000;
            dataSegmentBaseAddress = getCurrentConfiguration().dataSegmentBaseAddress //0x10000000;
            externBaseAddress = getCurrentConfiguration().externBaseAddress //0x10000000;
            globalPointer = getCurrentConfiguration().globalPointer //0x10008000;
            dataBaseAddress = getCurrentConfiguration().dataBaseAddress //0x10010000; // from SPIM not MIPS
            heapBaseAddress = getCurrentConfiguration().heapBaseAddress //0x10040000; // I think from SPIM not MIPS
            stackPointer = getCurrentConfiguration().stackPointer //0x7fffeffc;
            stackBaseAddress = getCurrentConfiguration().stackBaseAddress //0x7ffffffc;
            userHighAddress = getCurrentConfiguration().userHighAddress //0x7fffffff;
            kernelBaseAddress = getCurrentConfiguration().kernelBaseAddress //0x80000000;
            kernelTextBaseAddress = getCurrentConfiguration().kernelTextBaseAddress //0x80000000;
            exceptionHandlerAddress = getCurrentConfiguration().exceptionHandlerAddress //0x80000180;
            kernelDataBaseAddress = getCurrentConfiguration().kernelDataBaseAddress //0x90000000;
            memoryMapBaseAddress = getCurrentConfiguration().memoryMapBaseAddress //0xffff0000;
            kernelHighAddress = getCurrentConfiguration().kernelHighAddress //0xffffffff;
            dataSegmentLimitAddress = min(
                getCurrentConfiguration().dataSegmentLimitAddress.toDouble(),
                (dataSegmentBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
            textLimitAddress = min(
                getCurrentConfiguration().textLimitAddress.toDouble(),
                (textBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
            kernelDataSegmentLimitAddress = min(
                getCurrentConfiguration().kernelDataSegmentLimitAddress.toDouble(),
                (kernelDataBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
            kernelTextLimitAddress = min(
                getCurrentConfiguration().kernelTextLimitAddress.toDouble(),
                (kernelTextBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
            stackLimitAddress = max(
                getCurrentConfiguration().stackLimitAddress.toDouble(),
                (stackBaseAddress - BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
            memoryMapLimitAddress = min(
                getCurrentConfiguration().memoryMapLimitAddress.toDouble(),
                (memoryMapBaseAddress + BLOCK_LENGTH_WORDS * MMIO_TABLE_LENGTH * WORD_LENGTH_BYTES).toDouble()
            ).toInt()
        }

        @JvmStatic
        private fun getNewMemoryObserversCollection(): Vector<MemoryObservable> = Vector()

        // MARK: Utilities

        /** Determine if the given address is word-aligned. */
        @JvmStatic
        fun wordAligned(address: Int) = address % WORD_LENGTH_BYTES == 0

        /** Determine if the given address is double-word aligned. */
        @JvmStatic
        fun doubleWordAligned(address: Int) = address % (2 * WORD_LENGTH_BYTES) == 0

        /** Align the given address to the next full word boundary, if not already aligned. */
        @JvmStatic
        fun alignToWordBoundary(address: Int): Int {
            var address = address
            if (wordAligned(address)) return address
            if (address > 0) address += 4 - (address % WORD_LENGTH_BYTES)
            else address -= 4 - (address % WORD_LENGTH_BYTES)
            return address
        }

        /** Determine if the given address is in the MARS text segment. */
        @JvmStatic fun inTextSegment(address: Int) = address in textBaseAddress..<textLimitAddress

        /** Determine if the given address is in the MARS kernel text segment. */
        @JvmStatic fun inKernelTextSegment(address: Int) = address in kernelTextBaseAddress..<kernelTextLimitAddress

        /** Determine if the given address is in the MARS data segment. */
        @JvmStatic fun inDataSegment(address: Int) = address in dataSegmentBaseAddress..<dataSegmentLimitAddress

        /** Determine if the given address is in the MARS kernel data segment. */
        @JvmStatic fun inKernelDataSegment(address: Int) = address in kernelDataBaseAddress..<kernelDataSegmentLimitAddress

        /** Determine if the given address is in the MARS MMIO segment. */
        @JvmStatic fun inMemoryMapSegment(address: Int) = address in memoryMapBaseAddress..<kernelHighAddress

        private const val STORE = true
        private const val FETCH = false
    }

    init {
        initialize()
    }

    /**
     * Explicitly clear the contents of memory, typically done when assembling a new file.
     */
    fun clear() {
        setConfiguration()
        initialize()
    }

    /**
     * Determine whether the current memory configuration has a maximum address that can be stored in 16 bits.
     */
    val usingCompactMemoryConfiguration: Boolean get() = (kernelHighAddress and 0x00007fff) == kernelHighAddress

    /**
     * Initialize this instance of KMemory.
     */
    private fun initialize() {
        heapAddress = heapBaseAddress
        textBlockTable = Array(TEXT_BLOCK_TABLE_LENGTH) { arrayOf() }
        dataBlockTable = Array(BLOCK_TABLE_LENGTH) { null }
        kernelTextBlockTable = Array(TEXT_BLOCK_TABLE_LENGTH) { arrayOf() }
        kernelDataBlockTable = Array(BLOCK_TABLE_LENGTH) { null }
        stackBlockTable = Array(BLOCK_TABLE_LENGTH) { null }
        memoryMapBlockTable = Array(MMIO_TABLE_LENGTH) { null }
        // Call garbage collector on any table memory that was just deallocated.
        System.gc()
    }

    /**
     * Get the next available word-aligned heap address. There is no recycling and
     * no heap management; however, there is nearly 4MB of heap space available in MARS.
     *
     * @param numBytes Number of bytes requested. Should be multiple of 4; otherwise, the next highest multiple of four
     * will be allocated.
     * @return address of allocated heap storage.
     * @throws IllegalArgumentException if the number of requested bytes is negative or exceeds available heap storage
     */
    @Throws(IllegalArgumentException::class)
    fun allocateBytesFromHeap(numBytes: Int): Int {
        val result = heapAddress
        if (numBytes < 0)
            throw IllegalArgumentException("Requested amount of heap memory ($numBytes) cannot be negative!")
        var newHeapAddress = heapAddress + numBytes
        // Next highest multiple of four
        if (newHeapAddress % 4 != 0) newHeapAddress += 4 - newHeapAddress % 4
        if (newHeapAddress >= dataSegmentLimitAddress)
            throw IllegalArgumentException("Requested amount of heap memory ($numBytes) exceeds available heap storage!")
        heapAddress = newHeapAddress
        return result
    }

    /**
     * Set the byte order to either little endian or big endian. Default is little endian.
     */
    fun setByteOrder(order: ByteOrder) {
        byteOrder = order
    }

    /**
     * Retrieve memory byte order. Default is [ByteOrder.LITTLE_ENDIAN] like most PCs.
     *
     * @return Either [ByteOrder.LITTLE_ENDIAN] or [ByteOrder.BIG_ENDIAN].
     */
    fun getByteOrder(): ByteOrder = byteOrder

    // MARK: Setters

    /**
     * Starting at [address], write [value] up to a limit of [length] bytes.
     * This one does not check for word boundaries and copies one byte at a time.
     * If length == 1, takes value from low-order byte. If 2, takes from low-order half-word.
     *
     * @param address Starting address of Memory address to be set.
     * @param value   Value to be stored starting at that address.
     * @param length  Number of bytes to be written.
     * @return old value that was replaced by the set operation
     */
    @Throws(AddressErrorException::class)
    fun set(address: Int, value: Int, length: Int): Int {
        var oldValue = 0
        if (Globals.debug) println("memory[$address] set to $value ($length bytes)")
        val relativeByteAddress: Int
        if (inDataSegment(address)) {
            // The address is in the data segment. Write one byte at a time without considering boundaries.
            relativeByteAddress = address - dataSegmentBaseAddress
            oldValue = storeBytesInTable(dataBlockTable, relativeByteAddress, length, value)
        } else if (address in (stackLimitAddress + 1)..stackBaseAddress) {
            // The address is within the stack. Handle similarly to data segment write, but calculate the relative byte
            // "backwards", because the stack addresses grow downward from the base.
            relativeByteAddress = stackBaseAddress - address
            oldValue = storeBytesInTable(stackBlockTable, relativeByteAddress, length, value)
        } else if (inTextSegment(address)) {
            // We are in the text segment.
            // Make sure that self-modifying code is enabled; otherwise, this operation is not permitted.
            if (settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)) {
                val oldStatement = getStatement(address, false)
                if (oldStatement != null) oldValue = oldStatement.getBinaryStatement()
                setStatement(address, ProgramStatement(value, address))
            } else throw AddressErrorException(
                "Cannot write directly to the text segment! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_STORE
            )
        } else if (address in memoryMapBaseAddress..<memoryMapLimitAddress) {
            // We are in the memory-mapped I/O segment.
            relativeByteAddress = address - memoryMapBaseAddress
            oldValue = storeBytesInTable(memoryMapBlockTable, relativeByteAddress, length, value)
        } else if (inKernelDataSegment(address)) {
            // We are in the kernel data segment. Write one byte at a time, without regard to boundaries.
            relativeByteAddress = address - kernelDataBaseAddress
            oldValue = storeBytesInTable(kernelDataBlockTable, relativeByteAddress, length, value)
        } else if (inKernelTextSegment(address)) {
            // Developers must use setStatement() to write to the kernel text segment.
            throw AddressErrorException(
                "Developer: You MUST use setStatement() to write to the kernel text segment! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_STORE
            )
        } else {
            // The address falls outside the MARS addressing range.
            throw AddressErrorException(
                "The address is out of range! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_STORE
            )
        }
        notifyAnyObservers(AccessNotice.AccessType.WRITE, address, length, value)
        return oldValue
    }

    /**
     * Starting at the given word address, write the given value over four bytes (a word).
     * It must be written as-is, without adjusting for byte order (little vs big endian).
     * The address must be word-aligned.
     *
     * @param address Starting address of Memory address to be set.
     * @param value   Value to be stored starting at that address.
     * @return old value that was replaced by the set operation.
     * @throws AddressErrorException If address is not on word boundary.
     */
    @Throws(AddressErrorException::class)
    fun setRawWord(address: Int, value: Int): Int {
        val relative: Int
        var oldValue = 0
        if (address % WORD_LENGTH_BYTES != 0) throw AddressErrorException(
            "Store address not aligned on word boundary!",
            address,
            Exceptions.ADDRESS_EXCEPTION_STORE
        )
        if (inDataSegment(address)) {
            relative = (address - dataSegmentBaseAddress) shr 2
            oldValue = storeWordInTable(dataBlockTable, relative, value)
        } else if (address in (stackLimitAddress + 1)..stackBaseAddress) {
            relative = (stackBaseAddress - address) shr 2
            oldValue = storeWordInTable(stackBlockTable, relative, value)
        } else if (inTextSegment(address)) {
            if (settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)) {
                val oldStatement = getStatement(address, false)
                if (oldStatement != null) oldValue = oldStatement.getBinaryStatement()
                setStatement(address, ProgramStatement(value, address))
            } else throw AddressErrorException(
                "Cannot write directly to the text segment! ",
                address, Exceptions.ADDRESS_EXCEPTION_STORE
            )
        } else if (address in memoryMapBaseAddress..<memoryMapLimitAddress) {
            relative = (address - memoryMapBaseAddress) shr 2
            oldValue = storeWordInTable(memoryMapBlockTable, relative, value)
        } else if (inKernelDataSegment(address)) {
            relative = (address - kernelDataBaseAddress) shr 2
            oldValue = storeWordInTable(kernelDataBlockTable, relative, value)
        } else if (inKernelTextSegment(address)) {
            throw AddressErrorException(
                "Developer: You MUST use setStatement() to write to kernel text segment! ",
                address, Exceptions.ADDRESS_EXCEPTION_STORE
            )
        } else {
            throw AddressErrorException(
                "Cannot store address out of range! ",
                address, Exceptions.ADDRESS_EXCEPTION_STORE
            )
        }
        notifyAnyObservers(AccessNotice.AccessType.WRITE, address, WORD_LENGTH_BYTES, value)
        if (settings.getBackSteppingEnabled())
            Globals.program.getBackStepper()!!.addMemoryRestoreRawWord(address, oldValue)
        return oldValue
    }

    /**
     * Starting at the given word address, write the given value over four bytes (a word).
     * The address must be word-aligned.
     *
     * @param address Starting address of Memory address to be set.
     * @param value   Value to be stored starting at that address.
     * @return old value that was replaced by setWord operation.
     * @throws AddressErrorException If address is not on word boundary.
     */
    @Throws(AddressErrorException::class)
    fun setWord(address: Int, value: Int): Int {
        if (address % WORD_LENGTH_BYTES != 0)
            throw AddressErrorException(
                "Store address not aligned on a word boundary! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_STORE
            )
        return if (settings.getBackSteppingEnabled())
            Globals.program.getBackStepper()!!.addMemoryRestoreWord(address, set(address, value, WORD_LENGTH_BYTES))
        else set(address, value, WORD_LENGTH_BYTES)
    }

    /**
     * Starting at the given half-word address, write the lower 16 bits of given value into 2 bytes (a half-word).
     *
     * @param address Starting address of Memory address to be set.
     * @param value   Value to be stored starting at that address. Only the low-order 16 bits are used.
     * @return old value that was replaced by setHalf operation.
     * @throws AddressErrorException If address is not on half-word boundary.
     */
    @Throws(AddressErrorException::class)
    fun setHalf(address: Int, value: Int): Int {
        if (address % 2 != 0) throw AddressErrorException(
            "Store address not aligned on half-word boundary! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_STORE
        )
        return if (settings.getBackSteppingEnabled())
            Globals.program.getBackStepper()!!.addMemoryRestoreHalf(address, set(address, value, 2))
        else set(address, value, 2)
    }

    /**
     * Writes the low-order eight bits of the given value into the specified Memory byte.
     *
     * @param address Address of Memory byte to be set.
     * @param value   Value to be stored at that address. Only the low-order eight bits are used.
     * @return old value that was replaced by setByte operation.
     */
    @Throws(AddressErrorException::class)
    fun setByte(address: Int, value: Int): Int =
        if (settings.getBackSteppingEnabled())
            Globals.program.getBackStepper()!!.addMemoryRestoreByte(address, set(address, value, 1))
        else set(address, value, 1)

    /**
     * Write a 64-bit double value starting at the specified Memory address. Note that
     * high-order 32 bits are stored in higher (second) memory word regardless
     * of the selected endianness.
     *
     * @param address Starting address of Memory address to be set.
     * @param value   Value to be stored at that address.
     * @return old value that was replaced by setDouble operation.
     */
    @Throws(AddressErrorException::class)
    fun setDouble(address: Int, value: Double): Double {
        val longValue = value.toLongBits()
        val oldHighOrder = set(address + 4, Binary.highOrderLongToInt(longValue), 4)
        val oldLowOrder = set(address, Binary.lowOrderLongToInt(longValue), 4)
        return Binary.twoIntegersToLong(oldHighOrder, oldLowOrder).bitsToDouble()
    }

    /**
     * Store a ProgramStatement in the Text Segment.
     *
     * @param address   Starting address of Memory address to be set. Must be word boundary.
     * @param statement Machine code to be stored starting at that address -- for simulation
     *                  purposes, actually stores reference to ProgramStatement instead of 32-bit machine code.
     * @throws AddressErrorException If address is not on word boundary or is outside Text Segment.
     * @see ProgramStatement
     */
    @Throws(AddressErrorException::class)
    fun setStatement(address: Int, statement: ProgramStatement) {
        if (address % 4 != 0 || !(inTextSegment(address) || inKernelTextSegment(address)))
            throw AddressErrorException(
                "Cannot store address to text segment that's out of range or not aligned to a word boundary! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_STORE
            )
        if (Globals.debug) println("memory[$address] set to ${statement.getBinaryStatement()}")
        val (baseAddress, blockTable) =
            if (inTextSegment(address)) textBaseAddress to textBlockTable
            else kernelTextBaseAddress to kernelTextBlockTable
        storeProgramStatement(address, statement, baseAddress, blockTable)
    }

    // MARK: Getters

    /**
     * Starting at the given word address, read the given number of bytes (max 4).
     * This method does not check for word boundaries and copies one byte at a time.
     * If `length == 1`, it puts the value in the low-order byte.
     * If `length == 2`, it puts the value in the low-order half-word.
     *
     * @param address Starting address of memory address to be read
     * @param length The number of bytes to be read
     * @param notify Whether to notify observers of the read. Defaults to true.
     */
    @JvmOverloads
    @Throws(AddressErrorException::class)
    fun get(address: Int, length: Int, notify: Boolean = true): Int {
        val value: Int
        val relativeByteAddress: Int
        if (inDataSegment(address)) {
            relativeByteAddress = address - dataSegmentBaseAddress
            value = fetchBytesFromTable(dataBlockTable, relativeByteAddress, length)
        } else if (address in (stackLimitAddress + 1)..stackBaseAddress) {
            relativeByteAddress = stackBaseAddress - address
            value = fetchBytesFromTable(stackBlockTable, relativeByteAddress, length)
        } else if (address in memoryMapBaseAddress..<memoryMapLimitAddress) {
            relativeByteAddress = address - memoryMapBaseAddress
            value = fetchBytesFromTable(memoryMapBlockTable, relativeByteAddress, length)
        } else if (inTextSegment(address)) {
            if (settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)) {
                val stmt = getStatement(address, false)
                value = stmt?.getBinaryStatement() ?: 0
            } else throw AddressErrorException(
                "Cannot read directly from text segment! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        } else if (inKernelDataSegment(address)) {
            relativeByteAddress = address - kernelDataBaseAddress
            value = fetchBytesFromTable(kernelDataBlockTable, relativeByteAddress, length)
        } else if (inKernelTextSegment(address)) throw AddressErrorException(
            "DEVELOPER: You must use getStatement() to read from kernel text segment! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        ) else throw AddressErrorException(
            "Address out of range! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        if (notify) notifyAnyObservers(AccessNotice.AccessType.READ, address, length, value)
        return value
    }

    /**
     * Starting at the given word address, read a four-byte word as an integer.
     * It transfers the 32-bit value "raw" as stored in memory, and does not adjust
     * for byte order (big or little endian). Address must be word-aligned.
     *
     * @param address Starting address of word to be read.
     * @return Word (4-byte value) stored starting at that address.
     * @throws AddressErrorException If address is not on word boundary.
     */
    @Throws(AddressErrorException::class)
    fun getRawWord(address: Int): Int {
        // Note: the logic here is repeated in getRawWordOrNull() below. Logic is
        // simplified by having this method call getRawWordOrNull() and
        // returning either the int of its return value, or 0 if it returns null.
        // Doing so would be detrimental to simulation runtime performance, so
        // I decided to keep the duplicate logic.
        val value: Int
        val relative: Int
        if (address % WORD_LENGTH_BYTES != 0) throw AddressErrorException(
            "The address being read is not aligned on a word boundary! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        if (inDataSegment(address)) {
            relative = (address - dataSegmentBaseAddress) shr 2
            value = fetchWordFromTable(dataBlockTable, relative)
        } else if (address in (stackLimitAddress + 1)..stackBaseAddress) {
            relative = (stackBaseAddress - address) shr 2
            value = fetchWordFromTable(stackBlockTable, relative)
        } else if (address in memoryMapBaseAddress..<memoryMapLimitAddress) {
            relative = (address - memoryMapBaseAddress) shr 2
            value = fetchWordFromTable(memoryMapBlockTable, relative)
        } else if (inTextSegment(address)) {
            if (settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)) {
                val stmt = getStatement(address, false)
                value = stmt?.getBinaryStatement() ?: 0
            } else throw AddressErrorException(
                "Cannot read directly from text segment! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        } else if (inKernelDataSegment(address)) {
            relative = (address - kernelDataBaseAddress) shr 2
            value = fetchWordFromTable(kernelDataBlockTable, relative)
        } else if (inKernelTextSegment(address))
            throw AddressErrorException(
                "DEVELOPER: You must use getStatement() to read from kernel text segment! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        else
            throw AddressErrorException(
                "Address out of range! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        notifyAnyObservers(AccessNotice.AccessType.READ, address, WORD_LENGTH_BYTES, value)
        return value
    }

    /**
     * Starting at the given word address, read a four-byte word as an int and return Integer.
     * It transfers the 32-bit value "raw" as stored in memory, and does not adjust
     * for byte order (big or little endian). Address must be word-aligned.
     *
     * Returns null if reading from the text segment and there is no instruction at the
     * requested address.
     *
     * Returns null if reading from the data segment, and this is the
     * first reference to the MARS 4K memory allocation block (i.e., an array to
     * hold the memory has not been allocated).
     *
     * This method was developed by Greg Giberling of UC Berkeley to support the memory
     * dump feature that he implemented in Fall 2007.
     *
     * @param address Starting address of word to be read.
     * @return Word (4-byte value) stored starting at that address as an Integer. Conditions
     * that cause return value null are described above.
     * @throws AddressErrorException If address is not on word boundary.
     */
    @Throws(AddressErrorException::class)
    fun getRawWordOrNull(address: Int): Int? {
        var value: Int? = null
        val relative: Int
        if (address % WORD_LENGTH_BYTES != 0) throw AddressErrorException(
            "The address for this fetch operation is not aligned on the word boundary! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        if (inDataSegment(address)) {
            relative = (address - dataSegmentBaseAddress) shr 2
            value = fetchWordOrNullFromTable(dataBlockTable, relative)
        } else if (address in (stackLimitAddress + 1)..stackBaseAddress) {
            relative = (stackBaseAddress - address) shr 2
            value = fetchWordOrNullFromTable(stackBlockTable, relative)
        } else if (inTextSegment(address) || inKernelTextSegment(address)) {
            try {
                value =
                    if (getStatement(address, false) == null) null
                    else getStatement(address, false)?.getBinaryStatement()
            } catch (ignored: AddressErrorException) { }
        } else if (inKernelDataSegment(address)) {
            relative = (address - kernelDataBaseAddress) shr 2
            value = fetchWordOrNullFromTable(kernelDataBlockTable, relative)
        } else throw AddressErrorException("Address out of range! ", address, Exceptions.ADDRESS_EXCEPTION_LOAD)
        // Do not notify observers. This read operation is initiated by the
        // dump feature, not the executing MIPS program.
        return value
    }

    /**
     * Look for the first "null" memory value in an address range.
     * For text segment (binary code), this represents a word that does not contain an instruction.
     * We normally use this to find the end of the program.
     * For the data segment, this represents the first block of simulated memory (block length
     * currently 4K words) that has not been referenced by an assembled/executing program.
     *
     * @param baseAddress  lowest MIPS address to be searched; the starting point
     * @param limitAddress highest MIPS address to be searched
     * @return lowest address within the specified range that contains "null" value as described above.
     * @throws AddressErrorException if the base address is not on a word boundary
     */
    @Throws(AddressErrorException::class)
    fun getAddressOfFirstNull(baseAddress: Int, limitAddress: Int): Int {
        var address = baseAddress
        while (address < limitAddress) {
            if (getRawWordOrNull(address) == null) break
            address += WORD_LENGTH_BYTES
        }
        return address
    }

    /**
     * Starting at the given word address, read a four-byte word as an int.
     * Does not use "get()"; we can do it faster here knowing we're working only
     * with full words.
     *
     * @param address Starting address of word to be read.
     * @param notify Whether to notify observers. Defaults to true.
     * @return Word (4-byte value) stored starting at that address.
     * @throws AddressErrorException If address is not on word boundary.
     */
    @JvmOverloads
    @Throws(AddressErrorException::class)
    fun getWord(address: Int, notify: Boolean = true): Int {
        if (address % WORD_LENGTH_BYTES != 0)
            throw AddressErrorException(
                "Fetch address is not aligned on word boundary! ",
                address,
                Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        return get(address, WORD_LENGTH_BYTES, notify)
    }

    /**
     * Starting at the given word address, read a four-byte word as an int.
     * Does not use `get()`; we can do it faster here knowing we're working only with full words.
     * Observers are NOT notified.
     */
    @Deprecated(
        "Use getWord(address, false) instead.",
        ReplaceWith("getWord(address, false)"),
        DeprecationLevel.ERROR
    )
    @Throws(AddressErrorException::class)
    fun getWordNoNotify(address: Int) = getWord(address, false)

    /**
     * Starting at the given word address, read a two-byte word into the lower 16 bits of an int.
     *
     * @param address Starting address of word to be read.
     * @return Half-word (2-byte value) stored starting at that address, stored in lower 16 bits.
     * @throws AddressErrorException If address is not on half-word boundary.
     */
    @Throws(AddressErrorException::class)
    fun getHalf(address: Int): Int {
        if (address % 2 != 0) throw AddressErrorException(
            "Fetch address not aligned on half-word boundary! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        return get(address, 2)
    }

    /**
     * Reads specified memory byte into low-order eight bits of an integer.
     *
     * @param address Address of memory byte to read.
     * @return Value stored at that address. Only low-order eight bits used.
     */
    @Throws(AddressErrorException::class)
    fun getByte(address: Int): Int = get(address, 1)

    /**
     * Gets ProgramStatement from Text Segment.
     *
     * @param address Starting address of Memory address to be read. Must be word boundary.
     * @param notify Whether to notify observers. Defaults to true.
     * @return reference to ProgramStatement object associated with that address, or null if none.
     * @throws AddressErrorException If address is not on word boundary or is outside Text Segment.
     * @see ProgramStatement
     */
    @JvmOverloads
    @Throws(AddressErrorException::class)
    fun getStatement(address: Int, notify: Boolean = true): ProgramStatement? {
        if (!wordAligned(address)) throw AddressErrorException(
            "Fetch address for text segment not aligned to word boundary! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        if (!settings.getBooleanSetting(Settings.ENABLE_SELF_MODIFYING_CODE)
            && !(inTextSegment(address) || inKernelTextSegment(address))
        ) throw AddressErrorException(
            "Fetch address for text segment out of range! ",
            address,
            Exceptions.ADDRESS_EXCEPTION_LOAD
        )
        return if (inTextSegment(address))
            readProgramStatement(address, textBaseAddress, textBlockTable, notify)
        else if (inKernelTextSegment(address))
            readProgramStatement(address, kernelTextBaseAddress, kernelTextBlockTable, notify)
        else ProgramStatement(get(address, WORD_LENGTH_BYTES), address)
    }

    /**
     * Get ProgramStatement from Text Segment.
     */
    @Deprecated(
        "Use getStatement(address, false) instead.",
        ReplaceWith("getStatement(address, false)"),
        DeprecationLevel.ERROR
    )
    fun getStatementNoNotify(address: Int): ProgramStatement? = getStatement(address, false)

    /**
     * Register an observer for all memory areas.
     */
    override fun addObserver(obs: Observer) {
        try {
            this.addObserver(obs, 0, 0x7ffffffc)
            this.addObserver(obs, 0x80000000.toInt(), 0xfffffffc.toInt())
        } catch (aee: AddressErrorException) {
            println("Internal error in addObserver: $aee")
        }
    }

    /**
     * Method to accept an observer for a single memory address.
     *
     * @param obs Observer
     * @param addr The memory address, which must be on a word boundary.
     */
    @Throws(AddressErrorException::class)
    fun addObserver(obs: Observer, addr: Int) = addObserver(obs, addr, addr)

    /**
     * Method to accept an observer for a memory address range. The last byte included in the address range is the last
     * byte of the word specified by the ending address.
     * Note to observers: this class delegates Observable operations,
     * so notices will come from the delegate, not the memory object.
     *
     * @param obs The observer
     * @param startAddr The low end of the memory address range, which must be on a word boundary
     * @param endAddr The high end of the memory address range, which must be on a word boundary
     */
    @Throws(AddressErrorException::class)
    fun addObserver(obs: Observer, startAddr: Int, endAddr: Int) {
        if (startAddr % WORD_LENGTH_BYTES != 0)
            throw AddressErrorException(
                "Address not aligned on word boundary! ",
                startAddr, Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        if (endAddr != startAddr && endAddr % WORD_LENGTH_BYTES != 0)
            throw AddressErrorException(
                "Address not aligned on word boundary! ",
                endAddr, Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        // The upper half of the address space (above 0x7fffffff) has a sign bit of 1, and is seen as negative.
        if (startAddr >= 0 && endAddr < 0)
            throw AddressErrorException(
                "Range cannot cross 0x80000000; you must split it up! ",
                startAddr, Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        if (endAddr < startAddr)
            throw AddressErrorException(
                "End address of range < start address of range! ",
                startAddr, Exceptions.ADDRESS_EXCEPTION_LOAD
            )
        observables.add(MemoryObservable(obs, startAddr, endAddr))
    }

    /** Return number of observers. */
    override fun countObservers(): Int = observables.size

    /**
     * Remove the specified observer.
     *
     * @param obs Observer to be removed.
     */
    override fun deleteObserver(obs: Observer) {
        for (observable in observables) observable.deleteObserver(obs)
    }

    /**
     * Remove all memory observers.
     */
    override fun deleteObservers() {
        observables = getNewMemoryObserversCollection()
    }

    /**
     * Overridden to be unavailable. The notice that an Observer
     * receives does not come from the memory object itself, but
     * instead from a delegate.
     */
    override fun notifyObservers() = throw UnsupportedOperationException()

    /**
     * Overridden to be unavailable. The notice that an Observer
     * receives does not come from the memory object itself, but
     * instead from a delegate.
     */
    override fun notifyObservers(arg: Any?) = throw UnsupportedOperationException()

    /**
     * Private class whose objects will represent an observable-observer pair for a given memory address or range.
     */
    class MemoryObservable(
        obs: Observer,
        private val lowAddress: Int,
        private val highAddress: Int
    ) : Observable(), Comparable<MemoryObservable> {
        init {
            addObserver(obs)
        }

        fun match(address: Int) =
            address >= lowAddress && address <= highAddress - 1 + WORD_LENGTH_BYTES

        fun notifyObserver(notice: MemoryAccessNotice) {
            setChanged()
            notifyObservers(notice)
        }

        override fun compareTo(other: MemoryObservable): Int {
            if (lowAddress < other.lowAddress || lowAddress == other.lowAddress && highAddress < other.highAddress)
                return -1
            // FIXME: Change the return for this branch back to -1 if it turns out to be important.
            // FIXME: IntelliJ was reporting a "suspicious Comparable implementation".
            if (lowAddress > other.lowAddress || highAddress > other.highAddress)
                return 1
            return 0
        }
    }

    // MARK: Instance helper functions

    /** Notify any observers of memory operations that an action has occurred. */
    private fun notifyAnyObservers(type: AccessNotice.AccessType, address: Int, length: Int, value: Int) {
        if (Globals.gui == null && observables.isNotEmpty())
            for (it in observables)
                if (it.match(address))
                    it.notifyObserver(MemoryAccessNotice(type, address, length, value))
    }

    /**
     * Helper method to store one, two or four-byte value in the table that represents MIPS
     * memory. Originally used just for data segment, but now also used for stack.
     * Both use different tables, but use the same storage method and table size
     * and block size.
     */
    private fun storeBytesInTable(blockTable: Array<IntArray?>, relativeByteAddress: Int, length: Int, value: Int): Int =
        storeOrFetchBytesInTable(blockTable, relativeByteAddress, length, value, STORE)

    /**
     * Helper method to fetch one, two or four-byte value from the table that represents MIPS
     * memory. Originally used just for data segment, but now also used for stack.
     * Both use different tables, but use the same storage method and the same table size
     * and block size.
     */
    private fun fetchBytesFromTable(blockTable: Array<IntArray?>, relativeByteAddress: Int, length: Int) =
        storeOrFetchBytesInTable(blockTable, relativeByteAddress, length, 0, FETCH)

    /**
     * The helper's helper. Works for either storing or fetching, little or big endian.
     * When storing/fetching bytes, most of the work is calculating the correct array element(s)
     * and element byte(s). This method performs either store or fetch, as directed by its
     * client using STORE or FETCH in last arg.
     * Returns old value of replaced bytes for STORE.
     */
    @Synchronized
    private fun storeOrFetchBytesInTable(blockTable: Array<IntArray?>, relativeByteAddress: Int, length: Int, value: Int, op: Boolean): Int {
        var value = value
        var relativeByteAddress = relativeByteAddress
        var relativeWordAddress: Int
        var block: Int
        var offset: Int
        var bytePositionInMemory: Int
        val loopStopper = 3 - length
        var oldValue = 0
        if (blockTable.contentEquals(stackBlockTable)) {
            val delta = relativeByteAddress % 4
            if (delta != 0) {
                relativeByteAddress += (4 - delta) shl 1
            }
        }
        var bytePositionInValue = 3
        while (bytePositionInValue > loopStopper) {
            bytePositionInMemory = relativeByteAddress % 4
            relativeWordAddress = relativeByteAddress shr 2
            block = relativeWordAddress / BLOCK_LENGTH_WORDS
            offset = relativeWordAddress % BLOCK_LENGTH_WORDS
            if (blockTable[block] == null) {
                if (op == STORE) blockTable[block] = IntArray(BLOCK_LENGTH_WORDS)
                else return 0
            }
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) bytePositionInMemory = 3 - bytePositionInMemory
            if (op == STORE) {
                oldValue = replaceByte(
                    blockTable[block]!![offset], bytePositionInMemory,
                    oldValue, bytePositionInValue
                )
                blockTable[block]!![offset] = replaceByte(
                    value, bytePositionInValue,
                    blockTable[block]!![offset], bytePositionInMemory
                )
            } else { // op == FETCH
                value = replaceByte(
                    blockTable[block]!![offset], bytePositionInMemory,
                    value, bytePositionInValue
                )
            }
            relativeByteAddress++
            bytePositionInValue--
        }
        return if ((op == STORE)) oldValue else value
    }

    /**
     * Helper method to store a four-byte value in the table that represents MIPS memory.
     * Originally used just for data segment, but now also used for stack.
     * Both use different tables, but use the same storage method and the same table size
     * and block size. Assumes address is word aligned, no endian processing.
     */
    @Synchronized
    private fun storeWordInTable(blockTable: Array<IntArray?>, relative: Int, value: Int): Int {
        val block = relative / BLOCK_LENGTH_WORDS
        val offset = relative % BLOCK_LENGTH_WORDS
        if (blockTable[block] == null) {
            // First time writing to this block, so allocate the space.
            blockTable[block] = IntArray(BLOCK_LENGTH_WORDS)
        }
        val oldValue = blockTable[block]!![offset]
        blockTable[block]!![offset] = value
        return oldValue
    }

    /**
     * Helper method to fetch a four-byte value from the table that represents MIPS memory.
     * Originally used just for data segment, but now also used for stack.
     * Both use different tables, but use the same storage method and the same table size
     * and block size. Assumes address is word aligned, no endian processing.
     */
    @Synchronized
    private fun fetchWordFromTable(blockTable: Array<IntArray?>, relative: Int): Int {
        val block = relative / BLOCK_LENGTH_WORDS
        val offset = relative % BLOCK_LENGTH_WORDS
        // If null, it's the first reference to an address in this block. Assume it's initialized to zero.
        return if (blockTable[block] == null) 0 else blockTable[block]!![offset]
    }

    /**
     * Helper method to fetch a four-byte value from the table that represents MIPS memory.
     * Originally used just for data segment, but now also used for stack.
     * Both use different tables, but use the same storage method and the same table size
     * and block size. Assumes word alignment, no endian processing.
     *
     * This differs from "fetchWordFromTable()" in that it returns an Integer and
     * returns null instead of 0 if the 4K table has not been allocated.
     */
    @Synchronized
    private fun fetchWordOrNullFromTable(blockTable: Array<IntArray?>, relative: Int): Int? {
        val block = relative / BLOCK_LENGTH_WORDS
        val offset = relative % BLOCK_LENGTH_WORDS
        // First reference to an address in this block. Assume it's initialized to zero.
        return blockTable[block]?.get(offset)
    }

    /**
     * Returns result of substituting specified byte of source value into specified byte
     * of destination value. Byte positions are 0-1-2-3, listed from most to least
     * significant. No endian issues. This is a private helper method used by get() & set().
     */
    private fun replaceByte(sourceValue: Int, bytePosInSource: Int, destValue: Int, bytePosInDest: Int): Int =
        ((sourceValue shr (24 - (bytePosInSource shl 3)) and 0xFF) shl (24 - (bytePosInDest shl 3))) or
            (destValue and (0xFF shl (24 - (bytePosInDest shl 3))).inv())

    /**
     * Reverse the endianness of the given value.
     */
    private fun reverseBytes(source: Int): Int =
        (source shr 24 and 0x000000FF) or (source shr 8 and 0x0000FF00) or (source shl 8 and 0x00FF0000) or (source shl 24)

    /**
     * Store a program statement at the given address.
     * The address has already been verified as valid.
     * It may be either in user or kernel text segment, as specified by arguments.
     */
    private fun storeProgramStatement(
        address: Int,
        statement: ProgramStatement,
        baseAddress: Int,
        blockTable: Array<Array<ProgramStatement?>?>
    ) {
        val relative = (address - baseAddress) shr 2
        val block = relative / BLOCK_LENGTH_WORDS
        val offset = relative % BLOCK_LENGTH_WORDS
        if (block < TEXT_BLOCK_TABLE_LENGTH) {
            if (blockTable[block] == null)
                blockTable[block] = arrayOfNulls(BLOCK_LENGTH_WORDS)
            blockTable[block]!![offset] = statement
        }
    }

    /**
     * Read a program statement from the given address.
     * The address has already been verified as valid.
     * It may be either in user or kernel text segment, as specified by arguments.
     * Returns associated ProgramStatement or null if none.
     * The last parameter controls whether observers will be notified.
     */
    private fun readProgramStatement(
        address: Int,
        baseAddress: Int,
        blockTable: Array<Array<ProgramStatement?>?>,
        notify: Boolean
    ): ProgramStatement? {
        val relative = (address - baseAddress) shr 2
        val block = relative / TEXT_BLOCK_LENGTH_WORDS
        val offset = relative % TEXT_BLOCK_LENGTH_WORDS
        if (block < TEXT_BLOCK_TABLE_LENGTH) {
            if (blockTable[block] == null || blockTable[block]?.get(offset) == null) {
                // No instructions are stored in this block or offset.
                if (notify) notifyAnyObservers(AccessNotice.AccessType.READ, address, Instruction.INSTRUCTION_LENGTH, 0)
                return null
            } else {
                if (notify) notifyAnyObservers(AccessNotice.AccessType.READ, address, Instruction.INSTRUCTION_LENGTH, blockTable[block]!![offset]!!.getBinaryStatement())
                return blockTable[block]!![offset]!!
            }
        }
        if (notify) notifyAnyObservers(AccessNotice.AccessType.READ, address, Instruction.INSTRUCTION_LENGTH, 0)
        return null
    }
}