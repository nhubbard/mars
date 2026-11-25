package edu.missouristate.mars.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JavaClassesTest {
  @Test
  fun `primitive tokens and boxed tokens keep their different JVM meanings`() {
    assertTrue(javaClassOf<Int>().isPrimitive)
    assertEquals("int", javaClassOf<Int>().name)
    assertFalse(boxedClassOf<Int>().isPrimitive)
    assertEquals("java.lang.Integer", boxedClassOf<Int>().name)
    assertEquals("long", javaClassOf<Long>().name)
    assertEquals("java.lang.Long", boxedClassOf<Long>().name)
    assertEquals("boolean", javaClassOf<Boolean>().name)
    assertEquals("java.lang.Boolean", boxedClassOf<Boolean>().name)
  }

  @Test
  fun `reference and array tokens retain their identity and component types`() {
    assertSame(javaClassOf<String>(), boxedClassOf<String>())
    assertSame(javaClassOf<IntArray>(), boxedClassOf<IntArray>())
    assertTrue(javaClassOf<IntArray>().isArray)
    assertSame(javaClassOf<Int>(), javaClassOf<IntArray>().componentType)
    assertSame(javaClassOf<String>(), javaClassOf<Array<String>>().componentType)
  }
}
