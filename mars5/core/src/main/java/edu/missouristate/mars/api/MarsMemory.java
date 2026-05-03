package edu.missouristate.mars.api;

/**
 * Read/write access to simulated MIPS memory. All addresses are 32-bit unsigned values expressed as
 * Java {@code int}. Implementations must be thread-safe for reads from UI threads concurrent with
 * simulation on the simulator thread.
 */
public interface MarsMemory {

  /**
   * Reads a 32-bit word from the given word-aligned address.
   *
   * @param address word-aligned byte address
   * @return the 32-bit word stored at {@code address}
   * @throws MemoryAccessException if the address is out of range or misaligned
   */
  int getWord(int address) throws MemoryAccessException;

  /**
   * Writes a 32-bit word to the given word-aligned address.
   *
   * @param address word-aligned byte address
   * @param value the value to write
   * @throws MemoryAccessException if the address is out of range or misaligned
   */
  void setWord(int address, int value) throws MemoryAccessException;

  /**
   * Reads a 16-bit halfword (zero-extended to 32 bits) from the given halfword-aligned address.
   *
   * @param address halfword-aligned byte address
   * @return the 16-bit halfword stored at {@code address}, zero-extended
   * @throws MemoryAccessException if the address is out of range or misaligned
   */
  int getHalfWord(int address) throws MemoryAccessException;

  /**
   * Reads a single byte (zero-extended to 32 bits) from the given address.
   *
   * @param address byte address
   * @return the byte stored at {@code address}, zero-extended
   * @throws MemoryAccessException if the address is out of range
   */
  int getByte(int address) throws MemoryAccessException;

  /** Resets all memory to zero and reinstates the initial stack/global-pointer values. */
  void clear();
}
