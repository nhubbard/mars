package edu.missouristate.mars.mips.hardware.memory;

import edu.missouristate.mars.ProgramStatement;

/** Sparse text storage, addressed in words relative to its owning segment. */
public final class TextMemorySegment {
  private static final int BLOCK_WORDS = 1024;
  private final ProgramStatement[][] blocks;

  public TextMemorySegment(int capacity) {
    blocks = new ProgramStatement[capacity][];
  }

  public synchronized void store(int word, ProgramStatement value) {
    int block = word / BLOCK_WORDS, offset = word % BLOCK_WORDS;
    if (block < 0 || block >= blocks.length) {
      return;
    }
    if (blocks[block] == null) {
      blocks[block] = new ProgramStatement[BLOCK_WORDS];
    }
    blocks[block][offset] = value;
  }

  public synchronized ProgramStatement read(int word) {
    int block = word / BLOCK_WORDS, offset = word % BLOCK_WORDS;
    return block < 0 || block >= blocks.length || blocks[block] == null
        ? null
        : blocks[block][offset];
  }
}
