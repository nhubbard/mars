package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ExecutedInstructionCacheTest {
  @Test
  fun `hits reuse exact words and collisions preserve their own decoded operands`() {
    val cache = ExecutedInstructionCache(InstructionDefaults.forTarget(MarsTarget.MARS64))
    val first = cache.decode(0x10000000)
    assertEquals("beq", first.instruction?.name)
    assertEquals(4L, first.branchTargetOffset)
    assertSame(first, cache.decode(first.word))

    val collision = cache.decode(first.word xor 0x401)
    assertEquals("beq", collision.instruction?.name)
    assertEquals(4L + 0x401 * 4, collision.branchTargetOffset)
    assertSame(collision, cache.decode(collision.word))
    assertNotSame(first, cache.decode(first.word))
    assertEquals(first, cache.decode(first.word))
  }

  @Test
  fun `an encoding can be invalid for one catalog and valid for another`() {
    val wide = ExecutedInstructionCache(InstructionDefaults.forTarget(MarsTarget.MARS64))
    val narrow = ExecutedInstructionCache(InstructionDefaults.forTarget(MarsTarget.MARS32))
    val word = 0x01200008
    val invalid = wide.decode(word)
    assertNull(invalid.instruction)
    assertNull(invalid.branchTargetOffset)
    assertSame(invalid, wide.decode(word))
    assertEquals("jr", narrow.decode(word).instruction?.name)
    assertNull(narrow.decode(word).branchTargetOffset)
  }
}
