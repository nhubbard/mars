package edu.missouristate.mars.assembler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TokenListTest {
  @Test
  fun `iteration indices and size preserve order and cannot mutate the backing list`() {
    val tokens = TokenList()
    assertEquals(0, tokens.size)
    assertTrue(tokens.indices.isEmpty())
    val first = Token(TokenTypes.IDENTIFIER, "first", null, 1, 1)
    val second = Token(TokenTypes.IDENTIFIER, "second", null, 1, 7)
    tokens.add(first)
    tokens.add(second)
    assertEquals(tokens.size(), tokens.size)
    assertEquals(listOf(0, 1), tokens.indices.toList())
    assertEquals(listOf(first, second), tokens.toList())
    assertEquals(listOf(0 to first, 1 to second), tokens.withIndex().map { it.index to it.value })
    val iterator = tokens.iterator() as MutableIterator<Token>
    assertSame(first, iterator.next())
    assertThrows<UnsupportedOperationException> { iterator.remove() }
    assertEquals(2, tokens.size)
    tokens.freeze()
    val frozen = tokens.iterator() as MutableIterator<Token>
    assertSame(first, frozen.next())
    assertThrows<UnsupportedOperationException> { frozen.remove() }
    assertThrows<IllegalStateException> { frozen.next().type = TokenTypes.COMMENT }
    assertThrows<IllegalStateException> { tokens.add(first) }
    val clone = tokens.clone() as TokenList
    clone[0].type = TokenTypes.COMMENT
    clone.remove(1)
    assertEquals(1, clone.size)
    assertEquals(TokenTypes.IDENTIFIER, first.type)
    assertEquals(2, tokens.size)
  }

  @Test
  fun `size property retains dispatch to legacy subclass overrides`() {
    val tokens =
        object : TokenList() {
          override fun size(): Int = 3
        }
    assertEquals(3, tokens.size)
    assertEquals(listOf(0, 1, 2), tokens.indices.toList())
  }
}
