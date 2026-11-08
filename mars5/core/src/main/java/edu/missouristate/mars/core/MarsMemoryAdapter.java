package edu.missouristate.mars.core;

import edu.missouristate.mars.api.MarsMemory;
import edu.missouristate.mars.api.MemoryAccessException;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.simulator.BackStepper;
import java.util.Objects;

/** Exposes session memory through the public API without leaking hardware-specific exceptions. */
public final class MarsMemoryAdapter implements MarsMemory {
  private final MemoryState memory;
  private final BackStepper backStepper;

  public MarsMemoryAdapter(MemoryState memory) {
    this(memory, null);
  }

  public MarsMemoryAdapter(MemoryState memory, BackStepper backStepper) {
    this.memory = Objects.requireNonNull(memory);
    this.backStepper = backStepper;
  }

  @Override
  public int getWord(int address) throws MemoryAccessException {
    try {
      return memory.getWord(address);
    } catch (AddressErrorException error) {
      throw translate(error);
    }
  }

  @Override
  public void setWord(int address, int value) throws MemoryAccessException {
    try {
      memory.setWord(address, value);
    } catch (AddressErrorException error) {
      throw translate(error);
    }
  }

  @Override
  public int getHalfWord(int address) throws MemoryAccessException {
    try {
      return memory.getHalf(address);
    } catch (AddressErrorException error) {
      throw translate(error);
    }
  }

  @Override
  public int getByte(int address) throws MemoryAccessException {
    try {
      return memory.getByte(address);
    } catch (AddressErrorException error) {
      throw translate(error);
    }
  }

  @Override
  public void clear() {
    if (backStepper != null) {
      backStepper.clear();
    }
    memory.clear();
  }

  private static MemoryAccessException translate(AddressErrorException error) {
    var translated = new MemoryAccessException(error.getAddress(), error.getMessage());
    translated.initCause(error);
    return translated;
  }
}
