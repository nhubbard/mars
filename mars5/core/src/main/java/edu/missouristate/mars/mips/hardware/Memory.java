package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.*;
import edu.missouristate.mars.api.MarsSettings;
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
 * Legacy singleton memory facade. Session code uses MemoryState from its MarsContext instead.
 * Static addresses mirror only the legacy memory configuration, never an independent session.
 */
public class Memory {
  public static int textBaseAddress =
      MemoryConfigurations.getDefaultTextBaseAddress(); // 0x00400000;

  /** base address for (user) data segment: 0x10000000 * */
  public static int dataSegmentBaseAddress =
      MemoryConfigurations.getDefaultDataSegmentBaseAddress(); // 0x10000000;

  /** base address for .extern directive: 0x10000000 * */
  public static int externBaseAddress =
      MemoryConfigurations.getDefaultExternBaseAddress(); // 0x10000000;

  /** base address for storing globals * */
  public static int globalPointer = MemoryConfigurations.getDefaultGlobalPointer(); // 0x10008000;

  /**
   * base address for storage of non-global static data in data segment: 0x10010000 (from SPIM) *
   */
  public static int dataBaseAddress =
      MemoryConfigurations.getDefaultDataBaseAddress(); // 0x10010000; // from SPIM not MIPS

  /** base address for heap: 0x10040000 (I think from SPIM not MIPS) * */
  public static int heapBaseAddress =
      MemoryConfigurations.getDefaultHeapBaseAddress(); // 0x10040000; // I think from SPIM not MIPS

  /** starting address for stack: 0x7fffeffc (this is from SPIM not MIPS) * */
  public static int stackPointer = MemoryConfigurations.getDefaultStackPointer(); // 0x7fffeffc;

  /**
   * base address for stack: 0x7ffffffc (this is mine - start of highest word below kernel space) *
   */
  public static int stackBaseAddress =
      MemoryConfigurations.getDefaultStackBaseAddress(); // 0x7ffffffc;

  /** highest address accessible in user (not kernel) mode. * */
  public static int userHighAddress =
      MemoryConfigurations.getDefaultUserHighAddress(); // 0x7fffffff;

  /** kernel boundary. Only OS can access this or higher address * */
  public static int kernelBaseAddress =
      MemoryConfigurations.getDefaultKernelBaseAddress(); // 0x80000000;

  /** base address for kernel text segment: 0x80000000 * */
  public static int kernelTextBaseAddress =
      MemoryConfigurations.getDefaultKernelTextBaseAddress(); // 0x80000000;

  /** starting address for exception handlers: 0x80000180 * */
  public static int exceptionHandlerAddress =
      MemoryConfigurations.getDefaultExceptionHandlerAddress(); // 0x80000180;

  /** base address for kernel data segment: 0x90000000 * */
  public static int kernelDataBaseAddress =
      MemoryConfigurations.getDefaultKernelDataBaseAddress(); // 0x90000000;

  /** starting address for memory mapped I/O: 0xffff0000 (-65536) * */
  public static int memoryMapBaseAddress =
      MemoryConfigurations.getDefaultMemoryMapBaseAddress(); // 0xffff0000;

  /** highest address acessible in kernel mode. * */
  public static int kernelHighAddress =
      MemoryConfigurations.getDefaultKernelHighAddress(); // 0xffffffff;

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
  public static int heapAddress;

  private static final int BLOCK_LENGTH_WORDS = 1024;
  private static final int BLOCK_TABLE_LENGTH = 1024;
  private static final int TEXT_BLOCK_LENGTH_WORDS = 1024;
  private static final int TEXT_BLOCK_TABLE_LENGTH = 1024;
  private static final int MMIO_TABLE_LENGTH = 16;
  public static int dataSegmentLimitAddress =
      dataSegmentBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES;
  public static int textLimitAddress =
      textBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES;
  public static int kernelDataSegmentLimitAddress =
      kernelDataBaseAddress + BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES;
  public static int kernelTextLimitAddress =
      kernelTextBaseAddress + TEXT_BLOCK_LENGTH_WORDS * TEXT_BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES;
  public static int stackLimitAddress =
      stackBaseAddress - BLOCK_LENGTH_WORDS * BLOCK_TABLE_LENGTH * WORD_LENGTH_BYTES;
  public static int memoryMapLimitAddress =
      memoryMapBaseAddress + BLOCK_LENGTH_WORDS * MMIO_TABLE_LENGTH * WORD_LENGTH_BYTES;

  private static final MarsSettings LEGACY_SETTINGS =
      new MarsSettings() {
        private MarsSettings current() {
          return Globals.getSettings().getSimulationSettings();
        }

        public String getMemoryConfiguration() {
          return current().getMemoryConfiguration();
        }

        public boolean isDelayedBranchingEnabled() {
          return current().isDelayedBranchingEnabled();
        }

        public boolean isSelfModifyingCodeEnabled() {
          return current().isSelfModifyingCodeEnabled();
        }

        public boolean isWarningsAreErrors() {
          return current().isWarningsAreErrors();
        }

        public int getAssemblerErrorLimit() {
          return current().getAssemblerErrorLimit();
        }

        public boolean isExtendedAssemblerEnabled() {
          return current().isExtendedAssemblerEnabled();
        }
      };

  private static final Memory uniqueMemoryInstance = new Memory();
  private final MemoryState state;

  private Memory() {
    state =
        new MemoryState(
            MemoryConfigurations.createConfiguration("Default"),
            LEGACY_SETTINGS,
            ProgramStatement::new) {
          @Override
          protected boolean shouldNotifyObservers() {
            return Globals.program != null || !Globals.getGui().isGuiMode();
          }

          @Override
          protected boolean debugEnabled() {
            return Globals.debug;
          }
        };
    heapAddress = state.heapAddress;
  }

  /** Returns the legacy singleton. */
  public static Memory getInstance() {
    return uniqueMemoryInstance;
  }

  /** Transitional access for the legacy instruction execution context. */
  public MemoryState legacyState() {
    return state;
  }

  public static void setConfiguration() {
    uniqueMemoryInstance.state.setConfiguration(MemoryConfigurations.getCurrentConfiguration());
    syncAddresses();
  }

  private static void syncAddresses() {
    textBaseAddress = uniqueMemoryInstance.state.textBaseAddress;
    dataSegmentBaseAddress = uniqueMemoryInstance.state.dataSegmentBaseAddress;
    externBaseAddress = uniqueMemoryInstance.state.externBaseAddress;
    globalPointer = uniqueMemoryInstance.state.globalPointer;
    dataBaseAddress = uniqueMemoryInstance.state.dataBaseAddress;
    heapBaseAddress = uniqueMemoryInstance.state.heapBaseAddress;
    stackPointer = uniqueMemoryInstance.state.stackPointer;
    stackBaseAddress = uniqueMemoryInstance.state.stackBaseAddress;
    userHighAddress = uniqueMemoryInstance.state.userHighAddress;
    kernelBaseAddress = uniqueMemoryInstance.state.kernelBaseAddress;
    kernelTextBaseAddress = uniqueMemoryInstance.state.kernelTextBaseAddress;
    exceptionHandlerAddress = uniqueMemoryInstance.state.exceptionHandlerAddress;
    kernelDataBaseAddress = uniqueMemoryInstance.state.kernelDataBaseAddress;
    memoryMapBaseAddress = uniqueMemoryInstance.state.memoryMapBaseAddress;
    kernelHighAddress = uniqueMemoryInstance.state.kernelHighAddress;
    heapAddress = uniqueMemoryInstance.state.heapAddress;
    dataSegmentLimitAddress = uniqueMemoryInstance.state.dataSegmentLimitAddress;
    textLimitAddress = uniqueMemoryInstance.state.textLimitAddress;
    kernelDataSegmentLimitAddress = uniqueMemoryInstance.state.kernelDataSegmentLimitAddress;
    kernelTextLimitAddress = uniqueMemoryInstance.state.kernelTextLimitAddress;
    stackLimitAddress = uniqueMemoryInstance.state.stackLimitAddress;
    memoryMapLimitAddress = uniqueMemoryInstance.state.memoryMapLimitAddress;
  }

  public void clear() {
    setConfiguration();
    state.clear();
    syncAddresses();
  }

  public boolean usingCompactMemoryConfiguration() {
    return state.usingCompactMemoryConfiguration();
  }

  public int allocateBytesFromHeap(int numBytes) throws IllegalArgumentException {
    int result = state.allocateBytesFromHeap(numBytes);
    heapAddress = state.heapAddress;
    return result;
  }

  public void setByteOrder(boolean order) {
    state.setByteOrder(order);
  }

  public boolean getByteOrder() {
    return state.getByteOrder();
  }

  public int set(int address, int value, int length) throws AddressErrorException {
    return state.set(address, value, length);
  }

  public int setRawWord(int address, int value) throws AddressErrorException {
    int old = state.setRawWord(address, value);
    if (Globals.getSettings().getBackSteppingEnabled()) {
      Globals.program.getBackStepper().addMemoryRestoreRawWord(address, old);
    }
    return old;
  }

  public int setWord(int address, int value) throws AddressErrorException {
    int old = state.setWord(address, value);
    if (Globals.getSettings().getBackSteppingEnabled()) {
      Globals.program.getBackStepper().addMemoryRestoreWord(address, old);
    }
    return old;
  }

  public int setHalf(int address, int value) throws AddressErrorException {
    int old = state.setHalf(address, value);
    if (Globals.getSettings().getBackSteppingEnabled()) {
      Globals.program.getBackStepper().addMemoryRestoreHalf(address, old);
    }
    return old;
  }

  public int setByte(int address, int value) throws AddressErrorException {
    int old = state.setByte(address, value);
    if (Globals.getSettings().getBackSteppingEnabled()) {
      Globals.program.getBackStepper().addMemoryRestoreByte(address, old);
    }
    return old;
  }

  public double setDouble(int address, double value) throws AddressErrorException {
    return state.setDouble(address, value);
  }

  public void setStatement(int address, ProgramStatement statement) throws AddressErrorException {
    state.setStatement(address, statement);
  }

  public int get(int address, int length) throws AddressErrorException {
    return state.get(address, length);
  }

  public int getRawWord(int address) throws AddressErrorException {
    return state.getRawWord(address);
  }

  public Integer getRawWordOrNull(int address) throws AddressErrorException {
    return state.getRawWordOrNull(address);
  }

  public int getAddressOfFirstNull(int baseAddress, int limitAddress) throws AddressErrorException {
    return state.getAddressOfFirstNull(baseAddress, limitAddress);
  }

  public int getWord(int address) throws AddressErrorException {
    return state.getWord(address);
  }

  public int getWordNoNotify(int address) throws AddressErrorException {
    return state.getWordNoNotify(address);
  }

  public int getHalf(int address) throws AddressErrorException {
    return state.getHalf(address);
  }

  public int getByte(int address) throws AddressErrorException {
    return state.getByte(address);
  }

  public ProgramStatement getStatement(int address) throws AddressErrorException {
    return state.getStatement(address);
  }

  public ProgramStatement getStatementNoNotify(int address) throws AddressErrorException {
    return state.getStatementNoNotify(address);
  }

  public void addObserver(Consumer<MemoryAccessNotice> callback) {
    state.addObserver(callback);
  }

  public void addObserver(Consumer<MemoryAccessNotice> callback, int addr)
      throws AddressErrorException {
    state.addObserver(callback, addr);
  }

  public void addObserver(Consumer<MemoryAccessNotice> callback, int startAddr, int endAddr)
      throws AddressErrorException {
    state.addObserver(callback, startAddr, endAddr);
  }

  public void deleteObserver(Consumer<MemoryAccessNotice> callback) {
    state.deleteObserver(callback);
  }

  public void deleteObservers() {
    state.deleteObservers();
  }

  public int countObservers() {
    return state.countObservers();
  }

  public static boolean wordAligned(int address) {
    return MemoryState.wordAligned(address);
  }

  public static boolean doublewordAligned(int address) {
    return MemoryState.doublewordAligned(address);
  }

  public static int alignToWordBoundary(int address) {
    return MemoryState.alignToWordBoundary(address);
  }

  public static boolean inTextSegment(int address) {
    return uniqueMemoryInstance.state.inTextSegment(address);
  }

  public static boolean inKernelTextSegment(int address) {
    return uniqueMemoryInstance.state.inKernelTextSegment(address);
  }

  public static boolean inDataSegment(int address) {
    return uniqueMemoryInstance.state.inDataSegment(address);
  }

  public static boolean inKernelDataSegment(int address) {
    return uniqueMemoryInstance.state.inKernelDataSegment(address);
  }

  public static boolean inMemoryMapSegment(int address) {
    return uniqueMemoryInstance.state.inMemoryMapSegment(address);
  }
}
