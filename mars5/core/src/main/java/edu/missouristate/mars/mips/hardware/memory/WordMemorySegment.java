package edu.missouristate.mars.mips.hardware.memory;

import java.util.function.BooleanSupplier;

/**
 * Sparse word storage shared by data, kernel, stack and MMIO segments. Relative addresses and
 * permissions are supplied by the owning memory coordinator.
 */
public final class WordMemorySegment {
  private static final int BLOCK_LENGTH_WORDS = 1024;
  private final int[][] blockTable;
  private final boolean descending;
  private final BooleanSupplier littleEndian;

  public WordMemorySegment(int blocks, boolean descending, BooleanSupplier littleEndian) {
    blockTable = new int[blocks][];
    this.descending = descending;
    this.littleEndian = littleEndian;
  }

  /** Copies only allocated blocks. The destination segment retains its own byte-order supplier. */
  public synchronized Image capture(Runnable checkCancellation) {
    return new Image(copyBlocks(blockTable, checkCancellation));
  }

  public synchronized void load(Image image, Runnable checkCancellation) {
    var copy = copyBlocks(image.blocks, checkCancellation);
    System.arraycopy(copy, 0, blockTable, 0, blockTable.length);
  }

  private static int[][] copyBlocks(int[][] source, Runnable checkCancellation) {
    int[][] copy = new int[source.length][];
    for (int i = 0; i < source.length; i++) {
      if (source[i] != null) {
        checkCancellation.run();
        copy[i] = source[i].clone();
      }
    }
    return copy;
  }

  public static final class Image {
    private final int[][] blocks;

    private Image(int[][] blocks) {
      this.blocks = blocks;
    }
  }

  private static final boolean STORE = true;
  private static final boolean FETCH = false;

  public int storeBytes(int relativeByteAddress, int length, int value) {
    return storeOrFetchBytesInTable(relativeByteAddress, length, value, STORE);
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // Helper method to fetch 1, 2 or 4 byte value from table that represents MIPS
  // memory.  Originally used just for data segment, but now also used for stack.
  // Both use different tables but same storage method and same table size
  // and block size.
  //

  public int fetchBytes(int relativeByteAddress, int length) {
    return storeOrFetchBytesInTable(relativeByteAddress, length, 0, FETCH);
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // The helper's helper.  Works for either storing or fetching, little or big endian.
  // When storing/fetching bytes, most of the work is calculating the correct array element(s)
  // and element byte(s).  This method performs either store or fetch, as directed by its
  // client using STORE or FETCH in last arg.
  // Modified 29 Dec 2005 to return old value of replaced bytes, for STORE.
  //
  private synchronized int storeOrFetchBytesInTable(
      int relativeByteAddress, int length, int value, boolean op) {
    int relativeWordAddress, block, offset, bytePositionInMemory, bytePositionInValue;
    int oldValue = 0; // for STORE, return old values of replaced bytes
    int loopStopper = 3 - length;
    // IF added DPS 22-Dec-2008. NOTE: has NOT been tested with Big-Endian.
    // Fix provided by Saul Spatz; comments that follow are his.
    // If address in stack segment is 4k + m, with 0 < m < 4, then the
    // relativeByteAddress we want is stackBaseAddress - 4k + m, but the
    // address actually passed in is stackBaseAddress - (4k + m), so we
    // need to add 2m.  Because of the change in sign, we get the
    // expression 4-delta below in place of m.
    if (descending) {
      int delta = relativeByteAddress % 4;
      if (delta != 0) {
        relativeByteAddress += (4 - delta) << 1;
      }
    }
    for (bytePositionInValue = 3; bytePositionInValue > loopStopper; bytePositionInValue--) {
      bytePositionInMemory = relativeByteAddress % 4;
      relativeWordAddress = relativeByteAddress >> 2;
      block = relativeWordAddress / BLOCK_LENGTH_WORDS; // Block number
      offset = relativeWordAddress % BLOCK_LENGTH_WORDS; // Word within that block
      if (blockTable[block] == null) {
        if (op == STORE) {
          blockTable[block] = new int[BLOCK_LENGTH_WORDS];
        } else {
          return 0;
        }
      }
      if (littleEndian.getAsBoolean()) {
        bytePositionInMemory = 3 - bytePositionInMemory;
      }
      if (op == STORE) {
        oldValue =
            replaceByte(
                blockTable[block][offset], bytePositionInMemory, oldValue, bytePositionInValue);
        blockTable[block][offset] =
            replaceByte(
                value, bytePositionInValue, blockTable[block][offset], bytePositionInMemory);
      } else { // op == FETCH
        value =
            replaceByte(
                blockTable[block][offset], bytePositionInMemory, value, bytePositionInValue);
      }
      relativeByteAddress++;
    }
    return (op == STORE) ? oldValue : value;
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // Helper method to store 4 byte value in table that represents MIPS memory.
  // Originally used just for data segment, but now also used for stack.
  // Both use different tables but same storage method and same table size
  // and block size.  Assumes address is word aligned, no endian processing.
  // Modified 29 Dec 2005 to return overwritten value.

  public synchronized int storeWord(int relative, int value) {
    int block, offset, oldValue;
    block = relative / BLOCK_LENGTH_WORDS;
    offset = relative % BLOCK_LENGTH_WORDS;
    if (blockTable[block] == null) {
      // First time writing to this block, so allocate the space.
      blockTable[block] = new int[BLOCK_LENGTH_WORDS];
    }
    oldValue = blockTable[block][offset];
    blockTable[block][offset] = value;
    return oldValue;
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // Helper method to fetch 4 byte value from table that represents MIPS memory.
  // Originally used just for data segment, but now also used for stack.
  // Both use different tables but same storage method and same table size
  // and block size.  Assumes word alignment, no endian processing.
  //

  public synchronized int fetchWord(int relative) {
    int value;
    int block, offset;
    block = relative / BLOCK_LENGTH_WORDS;
    offset = relative % BLOCK_LENGTH_WORDS;
    if (blockTable[block] == null) {
      // first reference to an address in this block.  Assume initialized to 0.
      value = 0;
    } else {
      value = blockTable[block][offset];
    }
    return value;
  }

  ////////////////////////////////////////////////////////////////////////////////
  //
  // Helper method to fetch 4 byte value from table that represents MIPS memory.
  // Originally used just for data segment, but now also used for stack.
  // Both use different tables but same storage method and same table size
  // and block size.  Assumes word alignment, no endian processing.
  //
  // This differs from "fetchWord()" in that it returns an Integer and
  // returns null instead of 0 if the 4K table has not been allocated.  Developed
  // by Greg Gibeling of UC Berkeley, fall 2007.
  //

  public synchronized Integer fetchWordOrNull(int relative) {
    int value;
    int block, offset;
    block = relative / BLOCK_LENGTH_WORDS;
    offset = relative % BLOCK_LENGTH_WORDS;
    if (blockTable[block] == null) {
      // first reference to an address in this block.  Assume initialized to 0.
      return null;
    } else {
      value = blockTable[block][offset];
    }
    return value;
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // Returns result of substituting specified byte of source value into specified byte
  // of destination value. Byte positions are 0-1-2-3, listed from most to least
  // significant.  No endian issues.  This is a private helper method used by get() & set().
  private int replaceByte(int sourceValue, int bytePosInSource, int destValue, int bytePosInDest) {
    return
    // Set source byte value into destination byte position; set other 24 bits to 0's...
    ((sourceValue >> (24 - (bytePosInSource << 3)) & 0xFF) << (24 - (bytePosInDest << 3)))
        // and bitwise-OR it with...
        |
        // Set 8 bits in destination byte position to 0's, other 24 bits are unchanged.
        (destValue & ~(0xFF << (24 - (bytePosInDest << 3))));
  }
}
