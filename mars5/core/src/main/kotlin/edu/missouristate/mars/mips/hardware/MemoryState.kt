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
    decoder: (Int, Int) -> ProgramStatement?,
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
    private val listeners = CopyOnWriteArrayList<MemoryListener>()
    internal data class Reservation(val address: Long, val size: Int, val paired: Boolean = false)
    internal var reservation: Reservation? = null
    internal var wideDataEnabled = false
    internal var decoder = decoder
    internal var wideInstructionDecoder: ((Int, Long) -> ProgramStatement)? = null
    private lateinit var wideData: WordMemorySegment
    private lateinit var wideText: TextMemorySegment

    /** Linked operations use aligned ordinary storage in the single-core simulator. */
    internal fun checkLinkedAccess(address: Long, size: Int, exception: Int) {
        if (address % size != 0L) {
            throw AddressErrorException("address not aligned for linked access ", exception, address)
        }
        checkOrdinaryAccess(address, size, exception)
    }

    internal fun checkOrdinaryAccess(address: Long, size: Int, exception: Int) {
        if (wideDataEnabled && address in WIDE_DATA_BASE..<WIDE_DATA_BASE + SEGMENT_BYTES) {
            wideDataOffset(address, size, exception)
            return
        }
        checkOrdinaryAccess(narrowAddress(address, exception), size, exception)
    }

    private fun checkOrdinaryAccess(address: Int, size: Int, exception: Int) {
        val last = address.toLong() + size - 1
        if (last > Int.MAX_VALUE || !inSameOrdinarySegment(address, last.toInt())) {
            throw AddressErrorException("address outside ordinary storage ", exception, address)
        }
    }

    private fun inSameOrdinarySegment(first: Int, last: Int): Boolean =
        inDataSegment(first) && inDataSegment(last) ||
            inKernelDataSegment(first) && inKernelDataSegment(last) ||
            inStack(first) && inStack(last)

    private fun invalidateReservation(address: Long, length: Int) {
        val linked = reservation ?: return
        if (address < linked.address + linked.size && linked.address < address + length) {
            reservation = null
        }
    }

    internal fun narrowAddress(address: Long, exception: Int): Int {
        val narrow = address.toInt()
        if (address != narrow.toLong() && address != narrow.toUInt().toLong()) {
            throw AddressErrorException("Effective address is outside mapped memory: ", exception, address)
        }
        return narrow
    }

    private fun wideDataOffset(address: Long, size: Int, exception: Int): Int {
        if (!wideDataEnabled || address !in WIDE_DATA_BASE..WIDE_DATA_BASE + SEGMENT_BYTES - size) {
            throw AddressErrorException("Effective address is outside mapped memory: ", exception, address)
        }
        return (address - WIDE_DATA_BASE).toInt()
    }

    internal fun checkDataAddress(address: Long) {
        if (address != address.toInt().toLong()) wideDataOffset(address, 1, ADDRESS_EXCEPTION_LOAD)
    }

    internal fun readData(address: Long, size: Int): Int {
        if (address == address.toInt().toLong()) {
            return when (size) {
                1 -> getByte(address.toInt())
                2 -> getHalf(address.toInt())
                else -> getWord(address.toInt())
            }
        }
        return readWideData(address, size, notify = true)
    }

    private fun readWideData(address: Long, size: Int, notify: Boolean, instructionFetch: Boolean = false): Int {
        val value = wideData.fetchBytes(wideDataOffset(address, size, ADDRESS_EXCEPTION_LOAD), size)
        if (notify) notifyAddressObservers(READ, address, size, value, instructionFetch)
        return value
    }

    internal fun writeData(address: Long, size: Int, value: Int): Int {
        if (address == address.toInt().toLong()) {
            return when (size) {
                1 -> setByte(address.toInt(), value)
                2 -> setHalf(address.toInt(), value)
                else -> setWord(address.toInt(), value)
            }
        }
        val offset = wideDataOffset(address, size, ADDRESS_EXCEPTION_STORE)
        val previous = wideData.storeBytes(offset, size, value)
        invalidateReservation(address, size)
        notifyAddressObservers(WRITE, address, size, value)
        return previous
    }

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
    open fun captureData(checkCancellation: () -> Unit): DataImage = DataImage(
        byteOrder, heapAddress, dataBlockTable.capture(checkCancellation),
        kernelDataBlockTable.capture(checkCancellation), stackBlockTable.capture(checkCancellation),
        memoryMapBlockTable.capture(checkCancellation), wideData.capture(checkCancellation),
    )

    open fun loadData(image: DataImage, checkCancellation: () -> Unit) {
        reservation = null
        endian = image.littleEndian
        heapAddress = image.heapAddress
        dataBlockTable.load(image.data, checkCancellation)
        kernelDataBlockTable.load(image.kernelData, checkCancellation)
        stackBlockTable.load(image.stack, checkCancellation)
        memoryMapBlockTable.load(image.mmio, checkCancellation)
        wideData.load(image.wideData, checkCancellation)
    }

    @JvmRecord
    data class DataImage(
        val littleEndian: Boolean,
        val heapAddress: Int,
        val data: WordMemorySegment.Image,
        val kernelData: WordMemorySegment.Image,
        val stack: WordMemorySegment.Image,
        val mmio: WordMemorySegment.Image,
        val wideData: WordMemorySegment.Image,
    ) {
        fun hasSameContents(other: DataImage, checkCancellation: () -> Unit): Boolean =
            littleEndian == other.littleEndian && heapAddress == other.heapAddress &&
                data.hasSameContents(other.data, checkCancellation) &&
                kernelData.hasSameContents(other.kernelData, checkCancellation) &&
                stack.hasSameContents(other.stack, checkCancellation) &&
                mmio.hasSameContents(other.mmio, checkCancellation) &&
                wideData.hasSameContents(other.wideData, checkCancellation)
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
        dataSegmentLimitAddress = minOf(configuration.dataSegmentLimitAddress, dataSegmentBaseAddress + USER_DATA_BYTES)
        textLimitAddress = minOf(configuration.textLimitAddress, textBaseAddress + SEGMENT_BYTES)
        kernelDataSegmentLimitAddress = minOf(configuration.kernelDataSegmentLimitAddress, kernelDataBaseAddress + SEGMENT_BYTES)
        kernelTextLimitAddress = minOf(configuration.kernelTextLimitAddress, kernelTextBaseAddress + SEGMENT_BYTES)
        stackLimitAddress = maxOf(configuration.stackLimitAddress, stackBaseAddress - SEGMENT_BYTES)
        memoryMapLimitAddress = minOf(configuration.memoryMapLimitAddress, memoryMapBaseAddress + MMIO_BYTES)
    }

    open fun usingCompactMemoryConfiguration(): Boolean = (kernelHighAddress and 0x7fff) == kernelHighAddress

    private fun initialize() {
        reservation = null
        heapAddress = heapBaseAddress
        textBlockTable = TextMemorySegment(BLOCK_TABLE_LENGTH)
        kernelTextBlockTable = TextMemorySegment(BLOCK_TABLE_LENGTH)
        dataBlockTable = WordMemorySegment(USER_DATA_BLOCKS, false) { byteOrder }
        kernelDataBlockTable = WordMemorySegment(BLOCK_TABLE_LENGTH, false) { byteOrder }
        stackBlockTable = WordMemorySegment(BLOCK_TABLE_LENGTH, true) { byteOrder }
        memoryMapBlockTable = WordMemorySegment(MMIO_TABLE_LENGTH, false) { byteOrder }
        wideText = TextMemorySegment(BLOCK_TABLE_LENGTH)
        wideData = WordMemorySegment(BLOCK_TABLE_LENGTH, false) { byteOrder }
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
        invalidateReservation(address.toLong(), length)
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
        invalidateReservation(address.toLong(), WORD_LENGTH_BYTES)
        notifyAnyObservers(WRITE, address, WORD_LENGTH_BYTES, value)
        return previous
    }

    private fun replaceInstruction(address: Int, value: Int): Int {
        if (!settings.isSelfModifyingCodeEnabled) {
            throw AddressErrorException("Cannot write directly to text segment!", ADDRESS_EXCEPTION_STORE, address)
        }
        val previous = getStatementNoNotify(address)?.binaryStatement ?: 0
        setStatement(address, decoder(value, address))
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
    open fun getWord(address: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, FETCH_WORD_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        return readBytes(address, WORD_LENGTH_BYTES, notify = true)
    }

    @Throws(AddressErrorException::class)
    open fun getWordNoNotify(address: Int): Int {
        checkAlignment(address, WORD_LENGTH_BYTES, FETCH_WORD_ALIGNMENT, ADDRESS_EXCEPTION_LOAD)
        return readBytes(address, WORD_LENGTH_BYTES, notify = false)
    }

    val wideDataRange: LongRange?
        get() = if (wideDataEnabled) WIDE_DATA_BASE..<WIDE_DATA_BASE + SEGMENT_BYTES else null

    val wideTextRange: LongRange?
        get() = if (wideDataEnabled) WIDE_TEXT_BASE..<WIDE_TEXT_BASE + SEGMENT_BYTES else null

    val highestInspectableAddress: Long
        get() = if (wideDataEnabled) WIDE_TEXT_BASE + SEGMENT_BYTES - 1 else UInt.MAX_VALUE.toLong()

    /** Inspect a mapped word by its complete address without emitting an access event. */
    fun inspectWord(address: Long): Int? {
        val narrow = address.toInt()
        if (address == narrow.toLong() || address == narrow.toUInt().toLong()) return inspectWord(narrow)
        if (address and 3L != 0L) return null
        if (inWideText(address)) return getStatementNoNotify(address)?.binaryStatement
        return if (wideDataEnabled && address in WIDE_DATA_BASE..<WIDE_DATA_BASE + SEGMENT_BYTES) {
            readWideData(address, WORD_LENGTH_BYTES, notify = false)
        } else null
    }

    fun inUserText(address: Long): Boolean =
        if (address in 0..UInt.MAX_VALUE.toLong()) inTextSegment(address.toInt()) else inWideText(address)

    fun inUserData(address: Long): Boolean =
        if (address in 0..UInt.MAX_VALUE.toLong()) inDataSegment(address.toInt())
        else wideDataEnabled && address in WIDE_DATA_BASE..<WIDE_DATA_BASE + SEGMENT_BYTES

    /** Inspect one byte in user data without publishing a simulated access. */
    fun inspectDataByte(address: Long): Int? {
        if (!inUserData(address)) return null
        if (address > UInt.MAX_VALUE.toLong()) return readWideData(address, 1, notify = false)
        return readBytes(address.toInt(), 1, notify = false)
    }

    @Throws(AddressErrorException::class)
    fun getDumpWordOrNull(address: Long): Int? {
        if (address in 0..UInt.MAX_VALUE.toLong()) return getRawWordOrNull(address.toInt())
        if (address and 3L != 0L) throw AddressErrorException(RAW_FETCH_ALIGNMENT, ADDRESS_EXCEPTION_LOAD, address)
        if (inWideText(address)) return getStatementNoNotify(address)?.binaryStatement
        val offset = wideDataOffset(address, WORD_LENGTH_BYTES, ADDRESS_EXCEPTION_LOAD)
        return wideData.fetchWordOrNull(offset / WORD_LENGTH_BYTES)?.let {
            readWideData(address, WORD_LENGTH_BYTES, notify = false)
        }
    }

    fun inspectInstruction(address: Long): ProgramStatement? {
        val narrow = address.toInt()
        if (address == narrow.toLong() || address == narrow.toUInt().toLong()) return inspectInstruction(narrow)
        if (address and 3L != 0L) return null
        if (inWideText(address)) return getStatementNoNotify(address)
        if (!settings.isSelfModifyingCodeEnabled) return null
        val word = inspectWord(address) ?: return null
        return checkNotNull(wideInstructionDecoder)(word, address)
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
        return inspectWord(address)?.let { decoder(it, address) }
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

    internal fun checkInstructionAddress(address: Long): Long {
        val narrow = address.toInt()
        if (address == narrow.toLong() || address == narrow.toUInt().toLong()) return narrow.toLong()
        if (inWideText(address)) return address
        wideDataOffset(address, WORD_LENGTH_BYTES, ADDRESS_EXCEPTION_LOAD)
        if (!settings.isSelfModifyingCodeEnabled) {
            throw AddressErrorException("Effective address is outside mapped memory: ", ADDRESS_EXCEPTION_LOAD, address)
        }
        return address
    }

    fun getStatementNoNotify(address: Long): ProgramStatement? = fetchInstruction(address, notify = false)

    internal fun fetchInstruction(address: Long, notify: Boolean = true): ProgramStatement? {
        val mapped = checkInstructionAddress(address)
        if (mapped == mapped.toInt().toLong()) {
            return if (notify) fetchInstruction(mapped.toInt()) else getStatementNoNotify(mapped.toInt())
        }
        if (mapped % WORD_LENGTH_BYTES != 0L) {
            throw AddressErrorException("fetch address for text segment not aligned to word boundary ", ADDRESS_EXCEPTION_LOAD, mapped)
        }
        if (inWideText(mapped)) {
            val statement = wideText.read(((mapped - WIDE_TEXT_BASE) / WORD_LENGTH_BYTES).toInt())
            if (notify) notifyAddressObservers(READ, mapped, WORD_LENGTH_BYTES, statement?.binaryStatement ?: 0, true)
            return statement
        }
        val word = readWideData(mapped, WORD_LENGTH_BYTES, notify, instructionFetch = true)
        return checkNotNull(wideInstructionDecoder)(word, mapped)
    }

    internal fun setStatement(address: Long, statement: ProgramStatement) {
        if (address == address.toInt().toLong()) {
            setStatement(address.toInt(), statement)
        } else if (inWideText(address) && address % WORD_LENGTH_BYTES == 0L) {
            wideText.store(((address - WIDE_TEXT_BASE) / WORD_LENGTH_BYTES).toInt(), statement)
        } else {
            throw AddressErrorException("Invalid text segment address ", ADDRESS_EXCEPTION_STORE, address)
        }
    }

    internal fun setAssemblyDoubleword(address: Long, bits: Long) {
        if (address != address.toInt().toLong()) wideDataOffset(address, Long.SIZE_BYTES, ADDRESS_EXCEPTION_STORE)
        val low = bits.toInt()
        val high = (bits ushr Int.SIZE_BITS).toInt()
        setAssemblyData(address, if (byteOrder) low else high, Int.SIZE_BYTES)
        setAssemblyData(address + Int.SIZE_BYTES, if (byteOrder) high else low, Int.SIZE_BYTES)
    }

    fun inWideText(address: Long): Boolean =
        wideDataEnabled && address in WIDE_TEXT_BASE..<WIDE_TEXT_BASE + SEGMENT_BYTES

    internal fun setAssemblyData(address: Long, value: Int, size: Int) {
        if (address == address.toInt().toLong()) set(address.toInt(), value, size)
        else writeData(address, size, value)
    }

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
            else -> return decoder(readBytes(address, WORD_LENGTH_BYTES, notify, instructionFetch), address)
        }
        if (notify) notifyAnyObservers(READ, address, Instruction.INSTRUCTION_LENGTH, statement?.binaryStatement ?: 0, instructionFetch)
        return statement
    }

    open fun inTextSegment(address: Int): Boolean = address in textBaseAddress..<textLimitAddress

    open fun inKernelTextSegment(address: Int): Boolean = address in kernelTextBaseAddress..<kernelTextLimitAddress

    open fun inDataSegment(address: Int): Boolean = address in dataSegmentBaseAddress..<dataSegmentLimitAddress

    open fun inKernelDataSegment(address: Int): Boolean = address in kernelDataBaseAddress..<kernelDataSegmentLimitAddress

    // The stack grows down. Keep the lower boundary exclusive without offset arithmetic.
    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private fun inStack(address: Int): Boolean {
        if (address <= stackLimitAddress) return false
        if (address <= stackBaseAddress) return true
        // Compact layouts share storage with data, including its exclusive limit.
        return address.toLong() - stackBaseAddress < WORD_LENGTH_BYTES &&
            (!inDataSegment(stackBaseAddress) || inDataSegment(address))
    }

    private fun inMappedStorage(address: Int): Boolean = address in memoryMapBaseAddress..<memoryMapLimitAddress

    open fun addObserver(callback: (MemoryAccessNotice) -> Unit) {
        try {
            addObserver(callback, 0, 0x7ffffffc)
            addObserver(callback, Int.MIN_VALUE, 0xfffffffc.toInt())
        } catch (error: AddressErrorException) {
            println("Internal Error in MemoryState.addObserver: $error")
        }
    }

    @Throws(AddressErrorException::class)
    open fun addObserver(callback: (MemoryAccessNotice) -> Unit, address: Int) {
        addObserver(callback, address, address)
    }

    @Throws(AddressErrorException::class)
    open fun addObserver(callback: (MemoryAccessNotice) -> Unit, startAddr: Int, endAddr: Int) {
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

    open fun deleteObserver(callback: (MemoryAccessNotice) -> Unit) {
        listeners.removeIf { it.callback === callback }
    }

    private val addressObservers = CopyOnWriteArrayList<(MemoryAccessNotice) -> Unit>()

    /** Subscribe to execution and host accesses with full unsigned addresses. */
    fun addAddressObserver(callback: (MemoryAccessNotice) -> Unit) {
        addressObservers.addIfAbsent(callback)
    }

    fun deleteAddressObserver(callback: (MemoryAccessNotice) -> Unit) {
        addressObservers.remove(callback)
    }

    open fun countObservers(): Int = listeners.size

    private class MemoryListener(val callback: (MemoryAccessNotice) -> Unit, val low: Int, val high: Int) {
        fun matches(address: Int): Boolean = address >= low && address <= high - 1 + WORD_LENGTH_BYTES
    }

    private fun notifyAnyObservers(type: Int, address: Int, length: Int, value: Int, instructionFetch: Boolean = false) {
        notifyAddressObservers(type, address.toUInt().toLong(), length, value, instructionFetch)
        if (listeners.isEmpty()) return
        val notice = MemoryAccessNotice(type, address, length, value, instructionFetch)
        for (listener in listeners) {
            if (listener.matches(address)) listener.callback(notice)
        }
    }

    private fun notifyAddressObservers(type: Int, address: Long, length: Int, value: Int, instructionFetch: Boolean = false) {
        if (addressObservers.isEmpty()) return
        val notice = MemoryAccessNotice(type, address.toInt(), length, value, instructionFetch, address)
        for (observer in addressObservers) observer(notice)
    }

    companion object {
        const val WORD_LENGTH_BYTES = 4
        const val LITTLE_ENDIAN = true
        const val BIG_ENDIAN = false
        internal const val WIDE_DATA_BASE = 0x1_0000_0000L
        internal const val WIDE_TEXT_BASE = 0x1_0040_0000L

        private const val BLOCK_LENGTH_WORDS = 1024
        private const val BLOCK_TABLE_LENGTH = 1024
        private const val USER_DATA_BLOCKS = 4096
        private const val MMIO_TABLE_LENGTH = 16
        private const val USER_DATA_BYTES = BLOCK_LENGTH_WORDS * USER_DATA_BLOCKS * WORD_LENGTH_BYTES
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
    }
}
