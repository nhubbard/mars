package edu.missouristate.mars.mips.hardware

import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.READ
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.memory.TextMemorySegment
import edu.missouristate.mars.mips.hardware.memory.WordMemorySegment
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import edu.missouristate.mars.util.Binary
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiFunction
import java.util.function.Consumer
import kotlin.jvm.JvmRecord

/*
Copyright (c) 2003-2009,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Owns a simulation's memory, address layout, heap, byte order, and access observers. */
open class MemoryState(
    configuration: MemoryConfiguration,
    private val settings: MarsSettings,
    private val decoder: BiFunction<Int, Int, ProgramStatement?>,
) {
    @JvmField var textBaseAddress: Int = 0
    @JvmField var dataSegmentBaseAddress: Int = 0
    @JvmField var externBaseAddress: Int = 0
    @JvmField var globalPointer: Int = 0
    @JvmField var dataBaseAddress: Int = 0
    @JvmField var heapBaseAddress: Int = 0
    @JvmField var stackPointer: Int = 0
    @JvmField var stackBaseAddress: Int = 0
    @JvmField var userHighAddress: Int = 0
    @JvmField var kernelBaseAddress: Int = 0
    @JvmField var kernelTextBaseAddress: Int = 0
    @JvmField var exceptionHandlerAddress: Int = 0
    @JvmField var kernelDataBaseAddress: Int = 0
    @JvmField var memoryMapBaseAddress: Int = 0
    @JvmField var kernelHighAddress: Int = 0
    @JvmField var heapAddress: Int = 0
    @JvmField var dataSegmentLimitAddress: Int = 0
    @JvmField var textLimitAddress: Int = 0
    @JvmField var kernelDataSegmentLimitAddress: Int = 0
    @JvmField var kernelTextLimitAddress: Int = 0
    @JvmField var stackLimitAddress: Int = 0
    @JvmField var memoryMapLimitAddress: Int = 0

    private var layout = configuration
    private var endian = LITTLE_ENDIAN
    private var listeners = CopyOnWriteArrayList<MemoryListener>()

    private lateinit var dataBlockTable: WordMemorySegment
    private lateinit var kernelDataBlockTable: WordMemorySegment
    private lateinit var stackBlockTable: WordMemorySegment
    private lateinit var memoryMapBlockTable: WordMemorySegment
    private lateinit var textBlockTable: TextMemorySegment
    private lateinit var kernelTextBlockTable: TextMemorySegment

    init {
        setConfiguration(configuration)
        initialize()
    }

    /** Clears stored words, instructions, and heap allocation for the current layout. */
    open fun clear() = initialize()

    /** Captures a stopped session's sparse words. Text is stored with its instructions. */
    open fun captureData(checkCancellation: Runnable): DataImage = DataImage(
        byteOrder, heapAddress, dataBlockTable.capture(checkCancellation),
        kernelDataBlockTable.capture(checkCancellation), stackBlockTable.capture(checkCancellation),
        memoryMapBlockTable.capture(checkCancellation),
    )

    open fun loadData(image: DataImage, checkCancellation: Runnable) {
        endian = image.littleEndian
        heapAddress = image.heapAddress
        dataBlockTable.load(image.data, checkCancellation)
        kernelDataBlockTable.load(image.kernelData, checkCancellation)
        stackBlockTable.load(image.stack, checkCancellation)
        memoryMapBlockTable.load(image.mmio, checkCancellation)
    }

    @JvmRecord
    data class DataImage(
        val littleEndian: Boolean,
        val heapAddress: Int,
        val data: WordMemorySegment.Image,
        val kernelData: WordMemorySegment.Image,
        val stack: WordMemorySegment.Image,
        val mmio: WordMemorySegment.Image,
    ) {
        fun hasSameContents(other: DataImage, checkCancellation: Runnable): Boolean =
            littleEndian == other.littleEndian && heapAddress == other.heapAddress &&
                data.hasSameContents(other.data, checkCancellation) &&
                kernelData.hasSameContents(other.kernelData, checkCancellation) &&
                stack.hasSameContents(other.stack, checkCancellation) &&
                mmio.hasSameContents(other.mmio, checkCancellation)
    }

    open fun getConfiguration(): MemoryConfiguration = MemoryConfiguration(
        layout.configurationIdentifier(), layout.configurationName(),
        layout.configurationItemNames(), layout.configurationItemValues(),
    )

    /** Apply a layout while execution is stopped. Call [clear] to reset storage for the new layout. */
    fun setConfiguration(configuration: MemoryConfiguration) {
        layout = configuration
        textBaseAddress = configuration.textBaseAddress
        dataSegmentBaseAddress = configuration.dataSegmentBaseAddress
        externBaseAddress = configuration.externBaseAddress
        globalPointer = configuration.globalPointer
        dataBaseAddress = configuration.dataBaseAddress
        heapBaseAddress = configuration.heapBaseAddress
        stackPointer = configuration.stackPointer
        stackBaseAddress = configuration.stackBaseAddress
        userHighAddress = configuration.userHighAddress
        kernelBaseAddress = configuration.kernelBaseAddress
        kernelTextBaseAddress = configuration.kernelTextBaseAddress
        exceptionHandlerAddress = configuration.exceptionHandlerAddress
        kernelDataBaseAddress = configuration.kernelDataBaseAddress
        memoryMapBaseAddress = configuration.memoryMapBaseAddress
        kernelHighAddress = configuration.kernelHighAddress
        dataSegmentLimitAddress = minOf(configuration.dataSegmentLimitAddress, dataSegmentBaseAddress + SEGMENT_BYTES)
        textLimitAddress = minOf(configuration.textLimitAddress, textBaseAddress + SEGMENT_BYTES)
        kernelDataSegmentLimitAddress = minOf(configuration.kernelDataSegmentLimitAddress, kernelDataBaseAddress + SEGMENT_BYTES)
        kernelTextLimitAddress = minOf(configuration.kernelTextLimitAddress, kernelTextBaseAddress + SEGMENT_BYTES)
        stackLimitAddress = maxOf(configuration.stackLimitAddress, stackBaseAddress - SEGMENT_BYTES)
        memoryMapLimitAddress = minOf(configuration.memoryMapLimitAddress, memoryMapBaseAddress + MMIO_BYTES)
    }

    open fun usingCompactMemoryConfiguration(): Boolean = (kernelHighAddress and 0x7fff) == kernelHighAddress

    private fun initialize() {
        heapAddress = heapBaseAddress
        textBlockTable = TextMemorySegment(BLOCK_TABLE_LENGTH)
        kernelTextBlockTable = TextMemorySegment(BLOCK_TABLE_LENGTH)
        dataBlockTable = WordMemorySegment(BLOCK_TABLE_LENGTH, false, { byteOrder })
        kernelDataBlockTable = WordMemorySegment(BLOCK_TABLE_LENGTH, false, { byteOrder })
        stackBlockTable = WordMemorySegment(BLOCK_TABLE_LENGTH, true, { byteOrder })
        memoryMapBlockTable = WordMemorySegment(MMIO_TABLE_LENGTH, false, { byteOrder })
    }

    /** Reserve word-aligned heap storage and return its starting address. */
    @Throws(IllegalArgumentException::class)
    open fun allocateBytesFromHeap(numBytes: Int): Int {
        val result = heapAddress
        require(numBytes >= 0) { "request ($numBytes) is negative heap amount" }
        var end = heapAddress.toLong() + numBytes
        val remainder = end % WORD_LENGTH_BYTES
        if (remainder != 0L) end += WORD_LENGTH_BYTES - remainder
        require(end < dataSegmentLimitAddress) { "request ($numBytes) exceeds available heap storage" }
        heapAddress = end.toInt()
        return result
    }

    open var byteOrder: Boolean
        get() = endian
        set(order) {
            endian = order
        }

    /** Write low-order bytes starting at an address, with no alignment requirement. */
    @Throws(AddressErrorException::class)
    open fun set(address: Int, value: Int, length: Int): Int {
        val previous = when {
            inDataSegment(address) -> dataBlockTable.storeBytes(address - dataSegmentBaseAddress, length, value)
            inStack(address) -> stackBlockTable.storeBytes(stackBaseAddress - address, length, value)
            inTextSegment(address) -> replaceInstruction(address, value)
            inMappedStorage(address) -> memoryMapBlockTable.storeBytes(address - memoryMapBaseAddress, length, value)
            inKernelDataSegment(address) -> kernelDataBlockTable.storeBytes(address - kernelDataBaseAddress, length, value)
            inKernelTextSegment(address) -> throw AddressErrorException(KERNEL_TEXT_STORE, ADDRESS_EXCEPTION_STORE, address)
            else -> throw AddressErrorException("address out of range ", ADDRESS_EXCEPTION_STORE, address)
        }
        notifyAnyObservers(WRITE, address, length, value)
        return previous
    }

    /** Store an aligned word as represented in the underlying storage, without byte-order conversion. */
    @Throws(AddressErrorException::class)
    open fun setRawWord(address: Int, value: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, STORE_WORD_ALIGNMENT, ADDRESS_EXCEPTION_STORE)
        val previous = when {
            inDataSegment(address) -> dataBlockTable.storeWord((address - dataSegmentBaseAddress) shr 2, value)
            inStack(address) -> stackBlockTable.storeWord((stackBaseAddress - address) shr 2, value)
            inTextSegment(address) -> replaceInstruction(address, value)
            inMappedStorage(address) -> memoryMapBlockTable.storeWord((address - memoryMapBaseAddress) shr 2, value)
            inKernelDataSegment(address) -> kernelDataBlockTable.storeWord((address - kernelDataBaseAddress) shr 2, value)
            inKernelTextSegment(address) -> throw AddressErrorException(KERNEL_TEXT_STORE, ADDRESS_EXCEPTION_STORE, address)
            else -> throw AddressErrorException("store address out of range ", ADDRESS_EXCEPTION_STORE, address)
        }
        notifyAnyObservers(WRITE, address, WORD_LENGTH_BYTES, value)
        return previous
    }

    private fun replaceInstruction(address: Int, value: Int): Int {
        if (!settings.isSelfModifyingCodeEnabled) {
            throw AddressErrorException("Cannot write directly to text segment!", ADDRESS_EXCEPTION_STORE, address)
        }
        val previous = getStatementNoNotify(address)?.binaryStatement ?: 0
        setStatement(address, decoder.apply(value, address))
        return previous
    }

    @Throws(AddressErrorException::class)
    open fun setWord(address: Int, value: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, STORE_WORD_ALIGNMENT, ADDRESS_EXCEPTION_STORE)
        return set(address, value, WORD_LENGTH_BYTES)
    }

    @Throws(AddressErrorException::class)
    open fun setHalf(address: Int, value: Int): Int {
        checkAlignment(address, 2, "store address not aligned on halfword boundary ", ADDRESS_EXCEPTION_STORE)
        return set(address, value, 2)
    }

    @Throws(AddressErrorException::class)
    open fun setByte(address: Int, value: Int): Int = set(address, value, 1)

    /** Store the high word at the higher address in either byte order. */
    @Throws(AddressErrorException::class)
    open fun setDouble(address: Int, value: Double): Double {
        val bits = value.toBits()
        val oldHigh = set(address + WORD_LENGTH_BYTES, Binary.highOrderLongToInt(bits), WORD_LENGTH_BYTES)
        val oldLow = set(address, Binary.lowOrderLongToInt(bits), WORD_LENGTH_BYTES)
        return Double.fromBits(Binary.twoIntsToLong(oldHigh, oldLow))
    }

    @Throws(AddressErrorException::class)
    open fun setStatement(address: Int, statement: ProgramStatement?) {
        if (!wordAligned(address) || !(inTextSegment(address) || inKernelTextSegment(address))) {
            throw AddressErrorException(
                "store address to text segment out of range or not aligned to word boundary ",
                ADDRESS_EXCEPTION_STORE, address,
            )
        }
        if (inTextSegment(address)) {
            textBlockTable.store((address - textBaseAddress) shr 2, statement)
        } else {
            kernelTextBlockTable.store((address - kernelTextBaseAddress) shr 2, statement)
        }
    }

    /** Read the requested bytes into the low bits of the result, with no alignment requirement. */
    @Throws(AddressErrorException::class)
    open fun get(address: Int, length: Int): Int = readBytes(address, length, notify = true)

    private fun readBytes(address: Int, length: Int, notify: Boolean, instructionFetch: Boolean = false): Int {
        val value = when {
            inDataSegment(address) -> dataBlockTable.fetchBytes(address - dataSegmentBaseAddress, length)
            inStack(address) -> stackBlockTable.fetchBytes(stackBaseAddress - address, length)
            inMappedStorage(address) -> memoryMapBlockTable.fetchBytes(address - memoryMapBaseAddress, length)
            inTextSegment(address) -> readInstructionWord(address)
            inKernelDataSegment(address) -> kernelDataBlockTable.fetchBytes(address - kernelDataBaseAddress, length)
            inKernelTextSegment(address) -> throw AddressErrorException(KERNEL_TEXT_LOAD, ADDRESS_EXCEPTION_LOAD, address)
            else -> throw AddressErrorException("address out of range ", ADDRESS_EXCEPTION_LOAD, address)
        }
        if (notify) notifyAnyObservers(READ, address, length, value, instructionFetch)
        return value
    }

    private fun readInstructionWord(address: Int): Int {
        if (!settings.isSelfModifyingCodeEnabled) {
            throw AddressErrorException("Cannot read directly from text segment!", ADDRESS_EXCEPTION_LOAD, address)
        }
        return getStatementNoNotify(address)?.binaryStatement ?: 0
    }

    /** Read an aligned word as represented in the underlying storage. */
    @Throws(AddressErrorException::class)
    open fun getRawWord(address: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, RAW_FETCH_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        val value = when {
            inDataSegment(address) -> dataBlockTable.fetchWord((address - dataSegmentBaseAddress) shr 2)
            inStack(address) -> stackBlockTable.fetchWord((stackBaseAddress - address) shr 2)
            inMappedStorage(address) -> memoryMapBlockTable.fetchWord((address - memoryMapBaseAddress) shr 2)
            inTextSegment(address) -> readInstructionWord(address)
            inKernelDataSegment(address) -> kernelDataBlockTable.fetchWord((address - kernelDataBaseAddress) shr 2)
            inKernelTextSegment(address) -> throw AddressErrorException(KERNEL_TEXT_LOAD, ADDRESS_EXCEPTION_LOAD, address)
            else -> throw AddressErrorException("address out of range ", ADDRESS_EXCEPTION_LOAD, address)
        }
        notifyAnyObservers(READ, address, WORD_LENGTH_BYTES, value)
        return value
    }

    /** Read a raw word without notifying observers. An absent block or instruction returns null. */
    @Throws(AddressErrorException::class)
    open fun getRawWordOrNull(address: Int): Int? {
        checkAlignment(address, WORD_LENGTH_BYTES, RAW_FETCH_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        return when {
            inDataSegment(address) -> dataBlockTable.fetchWordOrNull((address - dataSegmentBaseAddress) shr 2)
            inStack(address) -> stackBlockTable.fetchWordOrNull((stackBaseAddress - address) shr 2)
            inTextSegment(address) || inKernelTextSegment(address) -> try {
                getStatementNoNotify(address)?.binaryStatement
            } catch (_: AddressErrorException) {
                null
            }
            inMappedStorage(address) -> memoryMapBlockTable.fetchWordOrNull((address - memoryMapBaseAddress) shr 2)
            inKernelDataSegment(address) -> kernelDataBlockTable.fetchWordOrNull((address - kernelDataBaseAddress) shr 2)
            else -> throw AddressErrorException("address out of range ", ADDRESS_EXCEPTION_LOAD, address)
        }
    }

    @Throws(AddressErrorException::class)
    open fun getAddressOfFirstNull(baseAddress: Int, limitAddress: Int): Int {
        var address = baseAddress
        while (address < limitAddress && getRawWordOrNull(address) != null) address += WORD_LENGTH_BYTES
        return address
    }

    @Throws(AddressErrorException::class)
    open fun getWord(address: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, FETCH_WORD_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        return readBytes(address, WORD_LENGTH_BYTES, notify = true)
    }

    @Throws(AddressErrorException::class)
    open fun getWordNoNotify(address: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, FETCH_WORD_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        return readBytes(address, WORD_LENGTH_BYTES, notify = false)
    }

    /** Inspect an aligned mapped word without notifying observers or enabling self-modifying code. */
    @Throws(AddressErrorException::class)
    open fun inspectWord(address: Int): Int? {
        if (!wordAligned(address)) return null
        if (inTextSegment(address) || inKernelTextSegment(address)) return getStatementNoNotify(address)?.binaryStatement
        if (inDataSegment(address) || inKernelDataSegment(address) || inStack(address) || inMappedStorage(address)) {
            return getWordNoNotify(address)
        }
        return null
    }

    @Throws(AddressErrorException::class)
    open fun inspectInstruction(address: Int): ProgramStatement? {
        if (!wordAligned(address)) return null
        if (inTextSegment(address) || inKernelTextSegment(address)) return getStatementNoNotify(address)
        if (!settings.isSelfModifyingCodeEnabled) return null
        return inspectWord(address)?.let { decoder.apply(it, address) }
    }

    @Throws(AddressErrorException::class)
    open fun getHalf(address: Int): Int {
        checkAlignment(address, 2, "fetch address not aligned on halfword boundary ", ADDRESS_EXCEPTION_LOAD)
        return get(address, 2)
    }

    @Throws(AddressErrorException::class)
    open fun getByte(address: Int): Int = get(address, 1)

    @Throws(AddressErrorException::class)
    open fun getStatement(address: Int): ProgramStatement? = readStatement(address, notify = true, instructionFetch = false)

    @Throws(AddressErrorException::class)
    open fun getStatementNoNotify(address: Int): ProgramStatement? = readStatement(address, notify = false, instructionFetch = false)

    /** Fetch the current instruction and mark the observer event as an instruction fetch. */
    @Throws(AddressErrorException::class)
    open fun fetchInstruction(address: Int): ProgramStatement? = readStatement(address, notify = true, instructionFetch = true)

    private fun readStatement(address: Int, notify: Boolean, instructionFetch: Boolean): ProgramStatement? {
        checkAlignment(
            address, WORD_LENGTH_BYTES, "fetch address for text segment not aligned to word boundary ", ADDRESS_EXCEPTION_LOAD,
        )
        if (!settings.isSelfModifyingCodeEnabled && !(inTextSegment(address) || inKernelTextSegment(address))) {
            throw AddressErrorException("fetch address for text segment out of range ", ADDRESS_EXCEPTION_LOAD, address)
        }
        val statement = when {
            inTextSegment(address) -> textBlockTable.read((address - textBaseAddress) shr 2)
            inKernelTextSegment(address) -> kernelTextBlockTable.read((address - kernelTextBaseAddress) shr 2)
            else -> return decoder.apply(readBytes(address, WORD_LENGTH_BYTES, notify, instructionFetch), address)
        }
        if (notify) notifyAnyObservers(READ, address, Instruction.INSTRUCTION_LENGTH, statement?.binaryStatement ?: 0, instructionFetch)
        return statement
    }

    open fun inTextSegment(address: Int): Boolean = address >= textBaseAddress && address < textLimitAddress

    open fun inKernelTextSegment(address: Int): Boolean = address >= kernelTextBaseAddress && address < kernelTextLimitAddress

    open fun inDataSegment(address: Int): Boolean = address >= dataSegmentBaseAddress && address < dataSegmentLimitAddress

    open fun inKernelDataSegment(address: Int): Boolean = address >= kernelDataBaseAddress && address < kernelDataSegmentLimitAddress

    open fun inMemoryMapSegment(address: Int): Boolean = address >= memoryMapBaseAddress && address < kernelHighAddress

    private fun inStack(address: Int): Boolean = address > stackLimitAddress && address <= stackBaseAddress

    private fun inMappedStorage(address: Int): Boolean = address >= memoryMapBaseAddress && address < memoryMapLimitAddress

    open fun addObserver(callback: Consumer<MemoryAccessNotice>) {
        try {
            addObserver(callback, 0, 0x7ffffffc)
            addObserver(callback, Int.MIN_VALUE, 0xfffffffc.toInt())
        } catch (error: AddressErrorException) {
            println("Internal Error in MemoryState.addObserver: $error")
        }
    }

    @Throws(AddressErrorException::class)
    open fun addObserver(callback: Consumer<MemoryAccessNotice>, address: Int) {
        addObserver(callback, address, address)
    }

    @Throws(AddressErrorException::class)
    open fun addObserver(callback: Consumer<MemoryAccessNotice>, startAddr: Int, endAddr: Int) {
        checkAlignment(startAddr, WORD_LENGTH_BYTES, OBSERVER_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        if (endAddr != startAddr && !wordAligned(endAddr)) {
            throw AddressErrorException(OBSERVER_ALIGNMENT, ADDRESS_EXCEPTION_LOAD, startAddr)
        }
        if (startAddr >= 0 && endAddr < 0) {
            throw AddressErrorException("range cannot cross 0x8000000; please split it up", ADDRESS_EXCEPTION_LOAD, startAddr)
        }
        if (endAddr < startAddr) {
            throw AddressErrorException("end address of range < start address of range ", ADDRESS_EXCEPTION_LOAD, startAddr)
        }
        listeners.add(MemoryListener(callback, startAddr, endAddr))
    }

    open fun deleteObserver(callback: Consumer<MemoryAccessNotice>) {
        listeners.removeIf { it.callback === callback }
    }

    open fun deleteObservers() {
        listeners = CopyOnWriteArrayList()
    }

    open fun countObservers(): Int = listeners.size

    private class MemoryListener(val callback: Consumer<MemoryAccessNotice>, val low: Int, val high: Int) {
        fun matches(address: Int): Boolean = address >= low && address <= high - 1 + WORD_LENGTH_BYTES
    }

    private fun notifyAnyObservers(type: Int, address: Int, length: Int, value: Int, instructionFetch: Boolean = false) {
        if (listeners.isEmpty()) return
        val notice = MemoryAccessNotice(type, address, length, value, instructionFetch)
        for (listener in listeners) {
            if (listener.matches(address)) listener.callback.accept(notice)
        }
    }

    companion object {
        const val WORD_LENGTH_BYTES = 4
        const val LITTLE_ENDIAN = true
        const val BIG_ENDIAN = false

        private const val BLOCK_LENGTH_WORDS = 1024
        private const val BLOCK_TABLE_LENGTH = 1024
        private const val MMIO_TABLE_LENGTH = 16
        private const val SEGMENT_BYTES = BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES
        private const val MMIO_BYTES = BLOCK_LENGTH_WORDS * MMIO_TABLE_LENGTH * WORD_LENGTH_BYTES

        private const val STORE_WORD_ALIGNMENT = "store address not aligned on word boundary "
        private const val RAW_FETCH_ALIGNMENT = "address for fetch not aligned on word boundary"
        private const val FETCH_WORD_ALIGNMENT = "fetch address not aligned on word boundary "
        private const val OBSERVER_ALIGNMENT = "address not aligned on word boundary "
        private const val KERNEL_TEXT_STORE = "DEVELOPER: You must use setStatement() to write to kernel text segment!"
        private const val KERNEL_TEXT_LOAD = "DEVELOPER: You must use getStatement() to read from kernel text segment!"

        private fun checkAlignment(address: Int, bytes: Int, message: String, type: Int) {
            if (address % bytes != 0) throw AddressErrorException(message, type, address)
        }

        @JvmStatic
        fun wordAligned(address: Int): Boolean = address % WORD_LENGTH_BYTES == 0

        @JvmStatic
        fun doublewordAligned(address: Int): Boolean = address % (2 * WORD_LENGTH_BYTES) == 0

        @JvmStatic
        fun alignToWordBoundary(address: Int): Int {
            val remainder = address % WORD_LENGTH_BYTES
            if (remainder == 0) return address
            val offset = WORD_LENGTH_BYTES - remainder
            return if (address > 0) address + offset else address - offset
        }
    }
}
