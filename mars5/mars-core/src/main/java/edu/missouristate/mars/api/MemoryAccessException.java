package edu.missouristate.mars.api;

/**
 * Thrown when a memory operation targets an invalid, misaligned, or out-of-range address. This is
 * the API-layer equivalent of the internal {@code AddressErrorException}.
 */
public class MemoryAccessException extends Exception {

  private final int address;

  public MemoryAccessException(int address, String message) {
    super(message);
    this.address = address;
  }

  /** Returns the address that triggered the error. */
  public int getAddress() {
    return address;
  }
}
