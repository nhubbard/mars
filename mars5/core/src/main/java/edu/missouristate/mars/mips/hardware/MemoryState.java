package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.MarsSettings;
import edu.missouristate.mars.mips.hardware.memory.TextMemorySegment;
import edu.missouristate.mars.mips.hardware.memory.WordMemorySegment;
import edu.missouristate.mars.mips.instructions.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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

/**
 * Owns a simulation's memory contents, address configuration, heap, byte order, and observers.
 * Settings and binary decoding are supplied by the owner. This class does not consult process-wide
 * state. InstructionContext records undo history for memory writes.
 *
 * @author Pete Sanderson
 * @version August 2003
 */

/////////////////////////////////////////////////////////////////////
// Memory defaults to little-endian; byte order is configurable per instance.
/////////////////////////////////////////////////////////////////////

public class MemoryState {

  /** base address for (user) text segment: 0x00400000 * */
  public int textBaseAddress; // 0x00400000;

  /** base address for (user) data segment: 0x10000000 * */
  public int dataSegmentBaseAddress; // 0x10000000;

  /** base address for .extern directive: 0x10000000 * */
  public int externBaseAddress; // 0x10000000;

  /** base address for storing globals * */
  public int globalPointer; // 0x10008000;

  /**
   * base address for storage of non-global static data in data segment: 0x10010000 (from SPIM) *
   */
  public int dataBaseAddress; // 0x10010000; // from SPIM not MIPS

  /** base address for heap: 0x10040000 (I think from SPIM not MIPS) * */
  public int heapBaseAddress; // 0x10040000; // I think from SPIM not MIPS

  /** starting address for stack: 0x7fffeffc (this is from SPIM not MIPS) * */
  public int stackPointer; // 0x7fffeffc;

  /**
   * base address for stack: 0x7ffffffc (this is mine - start of highest word below kernel space) *
   */
  public int stackBaseAddress; // 0x7ffffffc;

  /** highest address accessible in user (not kernel) mode. * */
  public int userHighAddress; // 0x7fffffff;

  /** kernel boundary. Only OS can access this or higher address * */
  public int kernelBaseAddress; // 0x80000000;

  /** base address for kernel text segment: 0x80000000 * */
  public int kernelTextBaseAddress; // 0x80000000;

  /** starting address for exception handlers: 0x80000180 * */
  public int exceptionHandlerAddress; // 0x80000180;

  /** base address for kernel data segment: 0x90000000 * */
  public int kernelDataBaseAddress; // 0x90000000;

  /** starting address for memory mapped I/O: 0xffff0000 (-65536) * */
  public int memoryMapBaseAddress; // 0xffff0000;

  /** highest address acessible in kernel mode. * */
  public int kernelHighAddress; // 0xffffffff;

  /** MIPS word length in bytes. * */
  // NOTE:  Much of the code is hardwired for 4 byte words.  Refactoring this is low priority.
  public static final int WORD_LENGTH_BYTES = 4;

  /**
   * Constant representing byte order of each memory word. Little-endian means lowest numbered byte
   * is right most [3][2][1][0].
   */
  public static final boolean LITTLE_ENDIAN = true;

  /**
   * Constant representing byte order of each memory word. Big-endian means lowest numbered byte is
   * left most [0][1][2][3].
   */
  public static final boolean BIG_ENDIAN = false;

  /** Current setting for endian (default LITTLE_ENDIAN) * */
  private boolean byteOrder = LITTLE_ENDIAN;

  public int heapAddress;

  // Each listener is paired with an address range it wants to observe.
  // Notification only fires for addresses that fall within a listener's range.
  Collection<MemoryListener> listeners = new CopyOnWriteArrayList<>();

  // The data segment is allocated in blocks of 1024 ints (4096 bytes).  Each block is
  // referenced by a "block table" entry, and the table has 1024 entries.  The capacity
  // is thus 1024 entries * 4096 bytes = 4 MB.  Should be enough to cover most
  // programs!!  Beyond that it would go to an "indirect" block (similar to Unix i-nodes),
  // which is not implemented.
  //
  // Although this scheme is an array of arrays, it is relatively space-efficient since
  // only the table is created initially. A 4096-byte block is not allocated until a value
  // is written to an address within it.  Thus most small programs will use only 8K bytes
  // of space (the table plus one block).  The index into both arrays is easily computed
  // from the address; access time is constant.
  //
  // SPIM stores statically allocated data (following first .data directive) starting
  // at location 0x10010000.  This is the first Data Segment word beyond the reach of $gp
  // used in conjunction with signed 16 bit immediate offset.  $gp has value 0x10008000
  // and with the signed 16 bit offset can reach from 0x10008000 - 0xFFFF = 0x10000000
  // (Data Segment base) to 0x10008000 + 0x7FFF = 0x1000FFFF (the byte preceding 0x10010000).
  //
  // Using my scheme, 0x10010000 falls at the beginning of the 17'th block -- table entry 16.
  // SPIM uses a heap base address of 0x10040000 which is not part of the MIPS specification.
  // (I don't have a reference for that offhand...)  Using my scheme, 0x10040000 falls at
  // the start of the 65'th block -- table entry 64.  That leaves (1024-64) * 4096 = 3,932,160
  // bytes of space available without going indirect.

  private static final int BLOCK_LENGTH_WORDS = 1024; // allocated blocksize 1024 ints == 4K bytes
  private static final int BLOCK_TABLE_LENGTH = 1024; // Each entry of table points to a block.
  private WordMemorySegment dataBlockTable;
  private WordMemorySegment kernelDataBlockTable;

  // The stack is modeled similarly to the data segment.  It cannot share the same
  // data structure because the stack base address is very large.  To store it in the
  // same data structure would require implementation of indirect blocks, which has not
  // been realized.  So the stack gets its own table of blocks using the same dimensions
  // and allocation scheme used for data segment.
  //
  // The other major difference is the stack grows DOWNWARD from its base address, not
  // upward.  I.e., the stack base is the largest stack address. This turns the whole
  // scheme for translating memory address to block-offset on its head!  The simplest
  // solution is to calculate relative address (offset from base) by subtracting the
  // desired address from the stack base address (rather than subtracting base address
  // from desired address).  Thus as the address gets smaller the offset gets larger.
  // Everything else works the same, so it shares some private helper methods with
  // data segment algorithms.

  private WordMemorySegment stackBlockTable;

  // Memory mapped I/O is simulated with a separate table using the same structure and
  // logic as data segment.  Memory is allocated in 4K byte blocks.  But since MMIO
  // address range is limited to 0xffff0000 to 0xfffffffc, there are only 64K bytes
  // total.  Thus there will be a maximum of 16 blocks, and I suspect never more than
  // one since only the first few addresses are typically used.  The only exception
  // may be a rogue program generating such addresses in a loop.  Note that the
  // MMIO addresses are interpreted by Java as negative numbers since it does not
  // have unsigned types.  As long as the absolute address is correctly translated
  // into a table offset, this is of no concern.

  private static final int MMIO_TABLE_LENGTH = 16; // Each entry of table points to a 4K block.
  private WordMemorySegment memoryMapBlockTable;

  // I use a similar scheme for storing instructions.  MIPS text segment ranges from
  // 0x00400000 all the way to data segment (0x10000000) a range of about 250 MB!  So
  // I'll provide table of blocks with similar capacity.  This differs from data segment
  // somewhat in that the block entries do not contain int's, but instead contain
  // references to ProgramStatement objects.

  private static final int TEXT_BLOCK_LENGTH_WORDS =
      1024; // allocated blocksize 1024 ints == 4K bytes
  private static final int TEXT_BLOCK_TABLE_LENGTH = 1024; // Each entry of table points to a block.
  private TextMemorySegment textBlockTable;
  private TextMemorySegment kernelTextBlockTable;

  // Set "top" address boundary to go with each "base" address.  This determines permissable
  // address range for user program.  Currently limit is 4MB, or 1024 * 1024 * 4 bytes based
  // on the table structures described above (except memory mapped IO, limited to 64KB by range).

  public int dataSegmentLimitAddress;
  public int textLimitAddress;
  public int kernelDataSegmentLimitAddress;
  public int kernelTextLimitAddress;
  public int stackLimitAddress;
  public int memoryMapLimitAddress;
  private final MarsSettings settings;
  private final BiFunction<Integer, Integer, ProgramStatement> decoder;
  private MemoryConfiguration configuration;

  /** Creates memory with independent contents and the supplied address layout. */
  public MemoryState(
      MemoryConfiguration configuration,
      MarsSettings settings,
      BiFunction<Integer, Integer, ProgramStatement> decoder) {
    this.settings = Objects.requireNonNull(settings);
    this.decoder = Objects.requireNonNull(decoder);
    setConfiguration(configuration);
    initialize();
  }

  /** Clears contents and heap allocation while retaining this instance's layout and byte order. */
  public void clear() {
    initialize();
  }

  /** Returns a defensive snapshot of the configured address layout. */
  public MemoryConfiguration getConfiguration() {
    return new MemoryConfiguration(
        configuration.configurationIdentifier(),
        configuration.configurationName(),
        configuration.configurationItemNames().clone(),
        configuration.configurationItemValues().clone());
  }

  /**
   * Replaces this instance's address layout without clearing storage. Call clear() before using the
   * new layout, or use MarsContext.setMemoryConfiguration() to reset memory and registers together.
   * Configuration changes require stopped execution.
   */
  public final void setConfiguration(MemoryConfiguration configuration) {
    Objects.requireNonNull(configuration);
    this.configuration =
        new MemoryConfiguration(
            configuration.configurationIdentifier(),
            configuration.configurationName(),
            configuration.configurationItemNames().clone(),
            configuration.configurationItemValues().clone());
    textBaseAddress = this.configuration.getTextBaseAddress(); // 0x00400000;
    dataSegmentBaseAddress = this.configuration.getDataSegmentBaseAddress(); // 0x10000000;
    externBaseAddress = this.configuration.getExternBaseAddress(); // 0x10000000;
    globalPointer = this.configuration.getGlobalPointer(); // 0x10008000;
    dataBaseAddress = this.configuration.getDataBaseAddress(); // 0x10010000; // from SPIM not MIPS
    heapBaseAddress =
        this.configuration.getHeapBaseAddress(); // 0x10040000; // I think from SPIM not MIPS
    stackPointer = this.configuration.getStackPointer(); // 0x7fffeffc;
    stackBaseAddress = this.configuration.getStackBaseAddress(); // 0x7ffffffc;
    userHighAddress = this.configuration.getUserHighAddress(); // 0x7fffffff;
    kernelBaseAddress = this.configuration.getKernelBaseAddress(); // 0x80000000;
    kernelTextBaseAddress = this.configuration.getKernelTextBaseAddress(); // 0x80000000;
    exceptionHandlerAddress = this.configuration.getExceptionHandlerAddress(); // 0x80000180;
    kernelDataBaseAddress = this.configuration.getKernelDataBaseAddress(); // 0x90000000;
    memoryMapBaseAddress = this.configuration.getMemoryMapBaseAddress(); // 0xffff0000;
    kernelHighAddress = this.configuration.getKernelHighAddress(); // 0xffffffff;
    dataSegmentLimitAddress =
        Math.min(
            this.configuration.getDataSegmentLimitAddress(),
            dataSegmentBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES);
    textLimitAddress =
        Math.min(
            this.configuration.getTextLimitAddress(),
            textBaseAddress
                + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES);
    kernelDataSegmentLimitAddress =
        Math.min(
            this.configuration.getKernelDataSegmentLimitAddress(),
            kernelDataBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES);
    kernelTextLimitAddress =
        Math.min(
            this.configuration.getKernelTextLimitAddress(),
            kernelTextBaseAddress
                + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES);
    stackLimitAddress =
        Math.max(
            this.configuration.getStackLimitAddress(),
            stackBaseAddress - BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES);
    memoryMapLimitAddress =
        Math.min(
            this.configuration.getMemoryMapLimitAddress(),
            memoryMapBaseAddress + BLOCK_LENGTH_WORDS * MMIO_TABLE_LENGTH * WORD_LENGTH_BYTES);
  }

  /**
   * Determine whether the current memory configuration has a maximum address that can be stored in
   * 16 bits.
   *
   * @return true if maximum address can be stored in 16 bits or less, false otherwise
   */
  public boolean usingCompactMemoryConfiguration() {
    return (kernelHighAddress & 0x00007fff) == kernelHighAddress;
  }

  private void initialize() {
    heapAddress = heapBaseAddress;
    textBlockTable = new TextMemorySegment(TEXT_BLOCK_TABLE_LENGTH);
    dataBlockTable =
        new WordMemorySegment(
            BLOCK_TABLE_LENGTH, false, this::getByteOrder); // array of null int[] references
    kernelTextBlockTable = new TextMemorySegment(TEXT_BLOCK_TABLE_LENGTH);
    kernelDataBlockTable = new WordMemorySegment(BLOCK_TABLE_LENGTH, false, this::getByteOrder);
    stackBlockTable = new WordMemorySegment(BLOCK_TABLE_LENGTH, true, this::getByteOrder);
    memoryMapBlockTable = new WordMemorySegment(MMIO_TABLE_LENGTH, false, this::getByteOrder);
  }

  /**
   * Returns the next available word-aligned heap address. There is no recycling and no heap
   * management! There is however nearly 4MB of heap space available in Mars.
   *
   * @param numBytes Number of bytes requested. Should be multiple of 4, otherwise next higher
   *     multiple of 4 allocated.
   * @return address of allocated heap storage.
   * @throws IllegalArgumentException if number of requested bytes is negative or exceeds available
   *     heap storage
   */
  public int allocateBytesFromHeap(int numBytes) throws IllegalArgumentException {
    int result = heapAddress;
    if (numBytes < 0) {
      throw new IllegalArgumentException("request (" + numBytes + ") is negative heap amount");
    }
    // Use wide arithmetic so oversized requests cannot wrap into apparently valid addresses.
    long newHeapAddress = (long) heapAddress + numBytes;
    if (newHeapAddress % 4 != 0) {
      newHeapAddress = newHeapAddress + (4 - newHeapAddress % 4); // next higher multiple of 4
    }
    if (newHeapAddress >= dataSegmentLimitAddress) {
      throw new IllegalArgumentException(
          "request (" + numBytes + ") exceeds available heap storage");
    }
    heapAddress = (int) newHeapAddress;
    return result;
  }

  /**
   * Set byte order to either LITTLE_ENDIAN or BIG_ENDIAN. Default is LITTLE_ENDIAN.
   *
   * @param order either LITTLE_ENDIAN or BIG_ENDIAN
   */
  public void setByteOrder(boolean order) {
    byteOrder = order;
  }

  /**
   * Retrieve memory byte order. Default is LITTLE_ENDIAN (like PCs).
   *
   * @return either LITTLE_ENDIAN or BIG_ENDIAN
   */
  public boolean getByteOrder() {
    return byteOrder;
  }

  /*  *******************************  THE SETTER METHODS  ******************************/

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given address, write the given value over the given number of bytes. This one
   * does not check for word boundaries, and copies one byte at a time. If length == 1, takes value
   * from low order byte. If 2, takes from low order half-word.
   *
   * @param address Starting address of Memory address to be set.
   * @param value Value to be stored starting at that address.
   * @param length Number of bytes to be written.
   * @return old value that was replaced by the set operation
   */

  // Allocates blocks if necessary.
  public int set(int address, int value, int length) throws AddressErrorException {
    int oldValue = 0;
    int relativeByteAddress;
    if (inDataSegment(address)) {
      // in data segment.  Will write one byte at a time, w/o regard to boundaries.
      relativeByteAddress =
          address - dataSegmentBaseAddress; // relative to data segment start, in bytes
      oldValue = storeBytesInTable(dataBlockTable, relativeByteAddress, length, value);
    } else if (address > stackLimitAddress && address <= stackBaseAddress) {
      // in stack.  Handle similarly to data segment write, except relative byte
      // address calculated "backward" because stack addresses grow down from base.
      relativeByteAddress = stackBaseAddress - address;
      oldValue = storeBytesInTable(stackBlockTable, relativeByteAddress, length, value);
    } else if (inTextSegment(address)) {
      // Burch Mod (Jan 2013): replace throw with call to setStatement
      // DPS adaptation 5-Jul-2013: either throw or call, depending on setting

      if (settings.isSelfModifyingCodeEnabled()) {
        ProgramStatement oldStatement = getStatementNoNotify(address);
        if (oldStatement != null) {
          oldValue = oldStatement.getBinaryStatement();
        }
        setStatement(address, decoder.apply(value, address));
      } else {
        throw new AddressErrorException(
            "Cannot write directly to text segment!", Exceptions.ADDRESS_EXCEPTION_STORE, address);
      }
    } else if (address >= memoryMapBaseAddress && address < memoryMapLimitAddress) {
      // memory mapped I/O.
      relativeByteAddress = address - memoryMapBaseAddress;
      oldValue = storeBytesInTable(memoryMapBlockTable, relativeByteAddress, length, value);
    } else if (inKernelDataSegment(address)) {
      // in kernel data segment.  Will write one byte at a time, w/o regard to boundaries.
      relativeByteAddress =
          address - kernelDataBaseAddress; // relative to data segment start, in bytes
      oldValue = storeBytesInTable(kernelDataBlockTable, relativeByteAddress, length, value);
    } else if (inKernelTextSegment(address)) {
      // DEVELOPER: PLEASE USE setStatement() TO WRITE TO KERNEL TEXT SEGMENT...
      throw new AddressErrorException(
          "DEVELOPER: You must use setStatement() to write to kernel text segment!",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    } else {
      // falls outside Mars addressing range
      throw new AddressErrorException(
          "address out of range ", Exceptions.ADDRESS_EXCEPTION_STORE, address);
    }
    notifyAnyObservers(AccessNotice.WRITE, address, length, value);
    return oldValue;
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, write the given value over 4 bytes (a word). It must be
   * written as is, without adjusting for byte order (little vs big endian). Address must be
   * word-aligned.
   *
   * @param address Starting address of Memory address to be set.
   * @param value Value to be stored starting at that address.
   * @return old value that was replaced by the set operation.
   * @throws AddressErrorException If address is not on word boundary.
   */
  public int setRawWord(int address, int value) throws AddressErrorException {
    int relative, oldValue = 0;
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "store address not aligned on word boundary ",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    }
    if (inDataSegment(address)) {
      // in data segment
      relative = (address - dataSegmentBaseAddress) >> 2; // convert byte address to words
      oldValue = storeWordInTable(dataBlockTable, relative, value);
    } else if (address > stackLimitAddress && address <= stackBaseAddress) {
      // in stack.  Handle similarly to data segment write, except relative
      // address calculated "backward" because stack addresses grow down from base.
      relative = (stackBaseAddress - address) >> 2; // convert byte address to words
      oldValue = storeWordInTable(stackBlockTable, relative, value);
    } else if (inTextSegment(address)) {
      // Burch Mod (Jan 2013): replace throw with call to setStatement
      // DPS adaptation 5-Jul-2013: either throw or call, depending on setting
      if (settings.isSelfModifyingCodeEnabled()) {
        ProgramStatement oldStatement = getStatementNoNotify(address);
        if (oldStatement != null) {
          oldValue = oldStatement.getBinaryStatement();
        }
        setStatement(address, decoder.apply(value, address));
      } else {
        throw new AddressErrorException(
            "Cannot write directly to text segment!", Exceptions.ADDRESS_EXCEPTION_STORE, address);
      }
    } else if (address >= memoryMapBaseAddress && address < memoryMapLimitAddress) {
      // memory mapped I/O.
      relative = (address - memoryMapBaseAddress) >> 2; // convert byte address to word
      oldValue = storeWordInTable(memoryMapBlockTable, relative, value);
    } else if (inKernelDataSegment(address)) {
      // in data segment
      relative = (address - kernelDataBaseAddress) >> 2; // convert byte address to words
      oldValue = storeWordInTable(kernelDataBlockTable, relative, value);
    } else if (inKernelTextSegment(address)) {
      // DEVELOPER: PLEASE USE setStatement() TO WRITE TO KERNEL TEXT SEGMENT...
      throw new AddressErrorException(
          "DEVELOPER: You must use setStatement() to write to kernel text segment!",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    } else {
      // falls outside Mars addressing range
      throw new AddressErrorException(
          "store address out of range ", Exceptions.ADDRESS_EXCEPTION_STORE, address);
    }
    notifyAnyObservers(AccessNotice.WRITE, address, WORD_LENGTH_BYTES, value);
    return oldValue;
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, write the given value over 4 bytes (a word). The address
   * must be word-aligned.
   *
   * @param address Starting address of Memory address to be set.
   * @param value Value to be stored starting at that address.
   * @return old value that was replaced by setWord operation.
   * @throws AddressErrorException If address is not on word boundary.
   */
  public int setWord(int address, int value) throws AddressErrorException {
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "store address not aligned on word boundary ",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    }
    return set(address, value, WORD_LENGTH_BYTES);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given halfword address, write the lower 16 bits of given value into 2 bytes (a
   * halfword).
   *
   * @param address Starting address of Memory address to be set.
   * @param value Value to be stored starting at that address. Only low order 16 bits used.
   * @return old value that was replaced by setHalf operation.
   * @throws AddressErrorException If address is not on halfword boundary.
   */
  public int setHalf(int address, int value) throws AddressErrorException {
    if (address % 2 != 0) {
      throw new AddressErrorException(
          "store address not aligned on halfword boundary ",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    }
    return set(address, value, 2);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Writes low order 8 bits of given value into specified Memory byte.
   *
   * @param address Address of Memory byte to be set.
   * @param value Value to be stored at that address. Only low order 8 bits used.
   * @return old value that was replaced by setByte operation.
   */
  public int setByte(int address, int value) throws AddressErrorException {
    return set(address, value, 1);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Writes 64 bit double value starting at specified Memory address. Note that high-order 32 bits
   * are stored in higher (second) memory word regardless of "endianness".
   *
   * @param address Starting address of Memory address to be set.
   * @param value Value to be stored at that address.
   * @return old value that was replaced by setDouble operation.
   */
  public double setDouble(int address, double value) throws AddressErrorException {
    int oldHighOrder, oldLowOrder;
    long longValue = Double.doubleToLongBits(value);
    oldHighOrder = set(address + 4, Binary.highOrderLongToInt(longValue), 4);
    oldLowOrder = set(address, Binary.lowOrderLongToInt(longValue), 4);
    return Double.longBitsToDouble(Binary.twoIntsToLong(oldHighOrder, oldLowOrder));
  }

  ////////////////////////////////////////////////////////////////////////////////
  /**
   * Stores ProgramStatement in Text Segment.
   *
   * @param address Starting address of Memory address to be set. Must be word boundary.
   * @param statement Machine code to be stored starting at that address -- for simulation purposes,
   *     actually stores reference to ProgramStatement instead of 32-bit machine code.
   * @throws AddressErrorException If address is not on word boundary or is outside Text Segment.
   * @see ProgramStatement
   */
  public void setStatement(int address, ProgramStatement statement) throws AddressErrorException {
    if (address % 4 != 0 || !(inTextSegment(address) || inKernelTextSegment(address))) {
      throw new AddressErrorException(
          "store address to text segment out of range or not aligned to word boundary ",
          Exceptions.ADDRESS_EXCEPTION_STORE,
          address);
    }
    if (inTextSegment(address)) {
      storeProgramStatement(address, statement, textBaseAddress, textBlockTable);
    } else {
      storeProgramStatement(address, statement, kernelTextBaseAddress, kernelTextBlockTable);
    }
  }

  /* *******************************  THE GETTER METHODS  ******************************/

  //////////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read the given number of bytes (max 4). This one does not
   * check for word boundaries, and copies one byte at a time. If length == 1, puts value in low
   * order byte. If 2, puts into low order half-word.
   *
   * @param address Starting address of Memory address to be read.
   * @param length Number of bytes to be read.
   * @return Value stored starting at that address.
   */
  public int get(int address, int length) throws AddressErrorException {
    return get(address, length, true);
  }

  // Does the real work, but includes option to NOT notify observers.
  private int get(int address, int length, boolean notify) throws AddressErrorException {
    int value;
    int relativeByteAddress;
    if (inDataSegment(address)) {
      // in data segment.  Will read one byte at a time, w/o regard to boundaries.
      relativeByteAddress =
          address - dataSegmentBaseAddress; // relative to data segment start, in bytes
      value = fetchBytesFromTable(dataBlockTable, relativeByteAddress, length);
    } else if (address > stackLimitAddress && address <= stackBaseAddress) {
      // in stack. Similar to data, except relative address computed "backward"
      relativeByteAddress = stackBaseAddress - address;
      value = fetchBytesFromTable(stackBlockTable, relativeByteAddress, length);
    } else if (address >= memoryMapBaseAddress && address < memoryMapLimitAddress) {
      // memory mapped I/O.
      relativeByteAddress = address - memoryMapBaseAddress;
      value = fetchBytesFromTable(memoryMapBlockTable, relativeByteAddress, length);
    } else if (inTextSegment(address)) {
      // Burch Mod (Jan 2013): replace throw with calls to getStatementNoNotify & getBinaryStatement
      // DPS adaptation 5-Jul-2013: either throw or call, depending on setting
      if (settings.isSelfModifyingCodeEnabled()) {
        ProgramStatement stmt = getStatementNoNotify(address);
        value = stmt == null ? 0 : stmt.getBinaryStatement();
      } else {
        throw new AddressErrorException(
            "Cannot read directly from text segment!", Exceptions.ADDRESS_EXCEPTION_LOAD, address);
      }
    } else if (inKernelDataSegment(address)) {
      // in kernel data segment.  Will read one byte at a time, w/o regard to boundaries.
      relativeByteAddress =
          address - kernelDataBaseAddress; // relative to data segment start, in bytes
      value = fetchBytesFromTable(kernelDataBlockTable, relativeByteAddress, length);
    } else if (inKernelTextSegment(address)) {
      // DEVELOPER: PLEASE USE getStatement() TO READ FROM KERNEL TEXT SEGMENT...
      throw new AddressErrorException(
          "DEVELOPER: You must use getStatement() to read from kernel text segment!",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    } else {
      // falls outside Mars addressing range
      throw new AddressErrorException(
          "address out of range ", Exceptions.ADDRESS_EXCEPTION_LOAD, address);
    }
    if (notify) {
      notifyAnyObservers(AccessNotice.READ, address, length, value);
    }
    return value;
  }

  /////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read a 4 byte word as an int. It transfers the 32 bit value
   * "raw" as stored in memory, and does not adjust for byte order (big or little endian). Address
   * must be word-aligned.
   *
   * @param address Starting address of word to be read.
   * @return Word (4-byte value) stored starting at that address.
   * @throws AddressErrorException If address is not on word boundary.
   */

  // Note: the logic here is repeated in getRawWordOrNull() below.  Logic is
  // simplified by having this method just call getRawWordOrNull() then
  // return either the int of its return value, or 0 if it returns null.
  // Doing so would be detrimental to simulation runtime performance, so
  // I decided to keep the duplicate logic.
  public int getRawWord(int address) throws AddressErrorException {
    int value;
    int relative;
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "address for fetch not aligned on word boundary",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    if (inDataSegment(address)) {
      // in data segment
      relative = (address - dataSegmentBaseAddress) >> 2; // convert byte address to words
      value = fetchWordFromTable(dataBlockTable, relative);
    } else if (address > stackLimitAddress && address <= stackBaseAddress) {
      // in stack. Similar to data, except relative address computed "backward"
      relative = (stackBaseAddress - address) >> 2; // convert byte address to words
      value = fetchWordFromTable(stackBlockTable, relative);
    } else if (address >= memoryMapBaseAddress && address < memoryMapLimitAddress) {
      // memory mapped I/O.
      relative = (address - memoryMapBaseAddress) >> 2;
      value = fetchWordFromTable(memoryMapBlockTable, relative);
    } else if (inTextSegment(address)) {
      // Burch Mod (Jan 2013): replace throw with calls to getStatementNoNotify & getBinaryStatement
      // DPS adaptation 5-Jul-2013: either throw or call, depending on setting
      if (settings.isSelfModifyingCodeEnabled()) {
        ProgramStatement stmt = getStatementNoNotify(address);
        value = stmt == null ? 0 : stmt.getBinaryStatement();
      } else {
        throw new AddressErrorException(
            "Cannot read directly from text segment!", Exceptions.ADDRESS_EXCEPTION_LOAD, address);
      }
    } else if (inKernelDataSegment(address)) {
      // in kernel data segment
      relative = (address - kernelDataBaseAddress) >> 2; // convert byte address to words
      value = fetchWordFromTable(kernelDataBlockTable, relative);
    } else if (inKernelTextSegment(address)) {
      // DEVELOPER: PLEASE USE getStatement() TO READ FROM KERNEL TEXT SEGMENT...
      throw new AddressErrorException(
          "DEVELOPER: You must use getStatement() to read from kernel text segment!",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    } else {
      // falls outside Mars addressing range
      throw new AddressErrorException(
          "address out of range ", Exceptions.ADDRESS_EXCEPTION_LOAD, address);
    }
    notifyAnyObservers(AccessNotice.READ, address, MemoryState.WORD_LENGTH_BYTES, value);
    return value;
  }

  /////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read a 4 byte word as an int and return Integer. It
   * transfers the 32 bit value "raw" as stored in memory, and does not adjust for byte order (big
   * or little endian). Address must be word-aligned.
   *
   * <p>Returns null if reading from text segment and there is no instruction at the requested
   * address. Returns null if reading from data segment and this is the first reference to the MARS
   * 4K memory allocation block (i.e., an array to hold the memory has not been allocated).
   *
   * <p>This method was developed by Greg Giberling of UC Berkeley to support the memory dump
   * feature that he implemented in Fall 2007.
   *
   * @param address Starting address of word to be read.
   * @return Word (4-byte value) stored starting at that address as an Integer. Conditions that
   *     cause return value null are described above.
   * @throws AddressErrorException If address is not on word boundary.
   */

  // See note above, with getRawWord(), concerning duplicated logic.

  public Integer getRawWordOrNull(int address) throws AddressErrorException {
    Integer value;
    int relative;
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "address for fetch not aligned on word boundary",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    if (inDataSegment(address)) {
      // in data segment
      relative = (address - dataSegmentBaseAddress) >> 2; // convert byte address to words
      value = fetchWordOrNullFromTable(dataBlockTable, relative);
    } else if (address > stackLimitAddress && address <= stackBaseAddress) {
      // in stack. Similar to data, except relative address computed "backward"
      relative = (stackBaseAddress - address) >> 2; // convert byte address to words
      value = fetchWordOrNullFromTable(stackBlockTable, relative);
    } else if (inTextSegment(address) || inKernelTextSegment(address)) {
      try {
        value =
            (getStatementNoNotify(address) == null)
                ? null
                : getStatementNoNotify(address).getBinaryStatement();
      } catch (AddressErrorException aee) {
        value = null;
      }
    } else if (inKernelDataSegment(address)) {
      // in kernel data segment
      relative = (address - kernelDataBaseAddress) >> 2; // convert byte address to words
      value = fetchWordOrNullFromTable(kernelDataBlockTable, relative);
    } else {
      // falls outside Mars addressing range
      throw new AddressErrorException(
          "address out of range ", Exceptions.ADDRESS_EXCEPTION_LOAD, address);
    }
    // Do not notify observers.  This read operation is initiated by the
    // dump feature, not the executing MIPS program.
    return value;
  }

  /**
   * Look for first "null" memory value in an address range. For text segment (binary code), this
   * represents a word that does not contain an instruction. Normally use this to find the end of
   * the program. For data segment, this represents the first block of simulated memory (block
   * length currently 4K words) that has not been referenced by an assembled/executing program.
   *
   * @param baseAddress lowest MIPS address to be searched; the starting point
   * @param limitAddress highest MIPS address to be searched
   * @return lowest address within specified range that contains "null" value as described above.
   * @throws AddressErrorException if the base address is not on a word boundary
   */
  public int getAddressOfFirstNull(int baseAddress, int limitAddress) throws AddressErrorException {
    int address = baseAddress;
    for (; address < limitAddress; address += MemoryState.WORD_LENGTH_BYTES) {
      if (getRawWordOrNull(address) == null) {
        break;
      }
    }
    return address;
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read a 4 byte word as an int. Does not use "get()"; we can
   * do it faster here knowing we're working only with full words.
   *
   * @param address Starting address of word to be read.
   * @return Word (4-byte value) stored starting at that address.
   * @throws AddressErrorException If address is not on word boundary.
   */
  public int getWord(int address) throws AddressErrorException {
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "fetch address not aligned on word boundary ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    return get(address, WORD_LENGTH_BYTES, true);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read a 4 byte word as an int. Does not use "get()"; we can
   * do it faster here knowing we're working only with full words. Observers are NOT notified.
   *
   * @param address Starting address of word to be read.
   * @return Word (4-byte value) stored starting at that address.
   * @throws AddressErrorException If address is not on word boundary.
   */
  public int getWordNoNotify(int address) throws AddressErrorException {
    if (address % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "fetch address not aligned on word boundary ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    return get(address, WORD_LENGTH_BYTES, false);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Starting at the given word address, read a 2 byte word into lower 16 bits of int.
   *
   * @param address Starting address of word to be read.
   * @return Halfword (2-byte value) stored starting at that address, stored in lower 16 bits.
   * @throws AddressErrorException If address is not on halfword boundary.
   */
  public int getHalf(int address) throws AddressErrorException {
    if (address % 2 != 0) {
      throw new AddressErrorException(
          "fetch address not aligned on halfword boundary ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    return get(address, 2);
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  /**
   * Reads specified Memory byte into low order 8 bits of int.
   *
   * @param address Address of Memory byte to be read.
   * @return Value stored at that address. Only low order 8 bits used.
   */
  public int getByte(int address) throws AddressErrorException {
    return get(address, 1);
  }

  ////////////////////////////////////////////////////////////////////////////////
  /**
   * Gets ProgramStatement from Text Segment.
   *
   * @param address Starting address of Memory address to be read. Must be word boundary.
   * @return reference to ProgramStatement object associated with that address, or null if none.
   * @throws AddressErrorException If address is not on word boundary or is outside Text Segment.
   * @see ProgramStatement
   */
  public ProgramStatement getStatement(int address) throws AddressErrorException {
    return getStatement(address, true);
  }

  ////////////////////////////////////////////////////////////////////////////////
  /**
   * Gets ProgramStatement from Text Segment without notifying observers.
   *
   * @param address Starting address of Memory address to be read. Must be word boundary.
   * @return reference to ProgramStatement object associated with that address, or null if none.
   * @throws AddressErrorException If address is not on word boundary or is outside Text Segment.
   * @see ProgramStatement
   */
  public ProgramStatement getStatementNoNotify(int address) throws AddressErrorException {
    return getStatement(address, false);
  }

  //////////

  private ProgramStatement getStatement(int address, boolean notify) throws AddressErrorException {
    if (!wordAligned(address)) {
      throw new AddressErrorException(
          "fetch address for text segment not aligned to word boundary ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    if (!settings.isSelfModifyingCodeEnabled()
        && !(inTextSegment(address) || inKernelTextSegment(address))) {
      throw new AddressErrorException(
          "fetch address for text segment out of range ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          address);
    }
    if (inTextSegment(address)) {
      return readProgramStatement(address, textBaseAddress, textBlockTable, notify);
    } else if (inKernelTextSegment(address)) {
      return readProgramStatement(address, kernelTextBaseAddress, kernelTextBlockTable, notify);
    } else {
      return decoder.apply(get(address, WORD_LENGTH_BYTES), address);
    }
  }

  /* ********************************  THE UTILITIES  *************************************/

  /**
   * Utility to determine if given address is word-aligned.
   *
   * @param address the address to check
   * @return true if address is word-aligned, false otherwise
   */
  public static boolean wordAligned(int address) {
    return (address % WORD_LENGTH_BYTES == 0);
  }

  /**
   * Utility to determine if given address is doubleword-aligned.
   *
   * @param address the address to check
   * @return true if address is doubleword-aligned, false otherwise
   */
  public static boolean doublewordAligned(int address) {
    return (address % (WORD_LENGTH_BYTES + WORD_LENGTH_BYTES) == 0);
  }

  /**
   * Utility method to align given address to next full word boundary, if not already aligned.
   *
   * @param address a memory address (any int value is potentially valid)
   * @return address aligned to next word boundary (divisible by 4)
   */
  public static int alignToWordBoundary(int address) {
    if (!wordAligned(address)) {
      if (address > 0) {
        address += (4 - (address % WORD_LENGTH_BYTES));
      } else {
        address -= (4 - (address % WORD_LENGTH_BYTES));
      }
    }
    return address;
  }

  /**
   * Handy little utility to find out if given address is in MARS text segment (starts at
   * MemoryState.textBaseAddress). Note that MARS does not implement the entire MIPS text segment
   * space, but it does implement enough for hundreds of thousands of lines of code.
   *
   * @param address integer memory address
   * @return true if that address is within MARS-defined text segment, false otherwise.
   */
  public boolean inTextSegment(int address) {
    return address >= textBaseAddress && address < textLimitAddress;
  }

  /**
   * Handy little utility to find out if given address is in MARS kernel text segment (starts at
   * MemoryState.kernelTextBaseAddress).
   *
   * @param address integer memory address
   * @return true if that address is within MARS-defined kernel text segment, false otherwise.
   */
  public boolean inKernelTextSegment(int address) {
    return address >= kernelTextBaseAddress && address < kernelTextLimitAddress;
  }

  /**
   * Handy little utility to find out if given address is in MARS data segment (starts at
   * MemoryState.dataSegmentBaseAddress). Note that MARS does not implement the entire MIPS data
   * segment space, but it does support at least 4MB.
   *
   * @param address integer memory address
   * @return true if that address is within MARS-defined data segment, false otherwise.
   */
  public boolean inDataSegment(int address) {
    return address >= dataSegmentBaseAddress && address < dataSegmentLimitAddress;
  }

  /**
   * Handy little utility to find out if given address is in MARS kernel data segment (starts at
   * MemoryState.kernelDataSegmentBaseAddress).
   *
   * @param address integer memory address
   * @return true if that address is within MARS-defined kernel data segment, false otherwise.
   */
  public boolean inKernelDataSegment(int address) {
    return address >= kernelDataBaseAddress && address < kernelDataSegmentLimitAddress;
  }

  /**
   * Handy little utility to find out if given address is in the Memory Map area starts at
   * MemoryState.memoryMapBaseAddress, range 0xffff0000 to 0xffffffff.
   *
   * @param address integer memory address
   * @return true if that address is within MARS-defined memory map (MMIO) area, false otherwise.
   */
  public boolean inMemoryMapSegment(int address) {
    return address >= memoryMapBaseAddress && address < kernelHighAddress;
  }

  // Typed memory listeners retain their individual address ranges.

  /** Register a listener for any memory address (full address space). */
  public void addObserver(Consumer<MemoryAccessNotice> callback) {
    try {
      this.addObserver(callback, 0, 0x7ffffffc);
      this.addObserver(callback, 0x80000000, 0xfffffffc);
    } catch (AddressErrorException aee) {
      System.out.println("Internal Error in MemoryState.addObserver: " + aee);
    }
  }

  /** Register a listener for a single word address. */
  public void addObserver(Consumer<MemoryAccessNotice> callback, int addr)
      throws AddressErrorException {
    this.addObserver(callback, addr, addr);
  }

  /** Register a listener for a memory address range [startAddr, endAddr] (word-aligned). */
  public void addObserver(Consumer<MemoryAccessNotice> callback, int startAddr, int endAddr)
      throws AddressErrorException {
    if (startAddr % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "address not aligned on word boundary ", Exceptions.ADDRESS_EXCEPTION_LOAD, startAddr);
    }
    if (endAddr != startAddr && endAddr % WORD_LENGTH_BYTES != 0) {
      throw new AddressErrorException(
          "address not aligned on word boundary ", Exceptions.ADDRESS_EXCEPTION_LOAD, startAddr);
    }
    if (startAddr >= 0 && endAddr < 0) {
      throw new AddressErrorException(
          "range cannot cross 0x8000000; please split it up",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          startAddr);
    }
    if (endAddr < startAddr) {
      throw new AddressErrorException(
          "end address of range < start address of range ",
          Exceptions.ADDRESS_EXCEPTION_LOAD,
          startAddr);
    }
    listeners.add(new MemoryListener(callback, startAddr, endAddr));
  }

  /** Remove all registrations for the given callback. */
  public void deleteObserver(Consumer<MemoryAccessNotice> callback) {
    listeners.removeIf(ml -> ml.callback() == callback);
  }

  /** Remove all memory listeners. */
  public void deleteObservers() {
    listeners = new CopyOnWriteArrayList<>();
  }

  /** Return number of registered listeners. */
  public int countObservers() {
    return listeners.size();
  }

  private record MemoryListener(
      Consumer<MemoryAccessNotice> callback, int lowAddress, int highAddress) {
    boolean match(int address) {
      return address >= lowAddress && address <= highAddress - 1 + WORD_LENGTH_BYTES;
    }
  }

  /*********************************  THE HELPERS  *************************************/

  private void notifyAnyObservers(int type, int address, int length, int value) {
    if (!this.listeners.isEmpty()) {
      MemoryAccessNotice notice = new MemoryAccessNotice(type, address, length, value);
      for (MemoryListener ml : listeners) {
        if (ml.match(address)) {
          ml.callback().accept(notice);
        }
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // Helper method to store 1, 2 or 4 byte value in table that represents MIPS
  // memory. Originally used just for data segment, but now also used for stack.
  // Both use different tables but same storage method and same table size
  // and block size.
  // Modified 29 Dec 2005 to return old value of replaced bytes.
  //
  private int storeBytesInTable(WordMemorySegment segment, int relative, int length, int value) {
    return segment.storeBytes(relative, length, value);
  }

  private int fetchBytesFromTable(WordMemorySegment segment, int relative, int length) {
    return segment.fetchBytes(relative, length);
  }

  private int storeWordInTable(WordMemorySegment segment, int relative, int value) {
    return segment.storeWord(relative, value);
  }

  private int fetchWordFromTable(WordMemorySegment segment, int relative) {
    return segment.fetchWord(relative);
  }

  private Integer fetchWordOrNullFromTable(WordMemorySegment segment, int relative) {
    return segment.fetchWordOrNull(relative);
  }

  ///////////////////////////////////////////////////////////////////////
  // Reverses byte sequence of given value.  Can use to convert between big and
  // little endian if needed.
  private int reverseBytes(int source) {
    return (source >> 24 & 0x000000FF)
        | (source >> 8 & 0x0000FF00)
        | (source << 8 & 0x00FF0000)
        | (source << 24);
  }

  ///////////////////////////////////////////////////////////////////////
  // Store a program statement at the given address.  Address has already been verified
  // as valid.  It may be either in user or kernel text segment, as specified by arguments.
  private void storeProgramStatement(
      int address, ProgramStatement statement, int baseAddress, TextMemorySegment segment) {
    segment.store((address - baseAddress) >> 2, statement);
  }

  private ProgramStatement readProgramStatement(
      int address, int baseAddress, TextMemorySegment segment, boolean notify) {
    ProgramStatement statement = segment.read((address - baseAddress) >> 2);
    if (notify) {
      notifyAnyObservers(
          AccessNotice.READ,
          address,
          Instruction.INSTRUCTION_LENGTH,
          statement == null ? 0 : statement.getBinaryStatement());
    }
    return statement;
  }
}
