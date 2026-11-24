package edu.missouristate.mars.plugin

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MarsSessionServiceTest {
  @Test
  fun `services own independent sessions and release them on disposal`() {
    val first = MarsSessionService()
    val second = MarsSessionService()
    try {
      val a = first.context
      val b = second.context
      assertSame(a, first.context)
      assertNotSame(a, b)
      assertSame(a.instructionSet, b.instructionSet)
      a.registerFile.updateRegister(8, 42)
      assertEquals(0, b.registerFile.getValue(8))
      assertNotSame(a.memory, b.memory)
      first.dispose()
      assertTrue(a.isClosing)
      assertThrows<IllegalStateException> { first.context }
      assertFalse(b.isClosing)
    } finally {
      first.dispose()
      second.dispose()
    }
  }

  @ParameterizedTest
  @EnumSource(MarsTarget::class)
  fun `launch factory starts on the requested target`(target: MarsTarget) {
    MarsSessionService.createMarsComponent(backstepLimit = 4, target = target).context().use {
        session ->
      assertEquals(target, session.target)
      assertSame(InstructionDefaults.forTarget(target), session.instructionSet)
      assertEquals(4, session.backStepper.capacity())
    }
  }

  @Test
  fun `disposing an unused service does not create a session`() {
    val service = MarsSessionService()
    service.dispose()
    service.dispose()
    assertThrows<IllegalStateException> { service.context }
  }

  @Test
  fun `bootstrap frontend routes messages and refuses input until a console is attached`() {
    val service = MarsSessionService()
    try {
      val frontend = service.context.gui
      assertFalse(frontend.isGuiMode)
      frontend.postMessage("MARS bootstrap test message")
      frontend.postRunMessage("MARS bootstrap test output")
      frontend.clearRunConsole()
      assertThrows<UnsupportedOperationException> { frontend.readInput(10) }
      assertThrows<UnsupportedOperationException> { frontend.readInputWithPrompt("input") }
    } finally {
      service.dispose()
    }
  }
}
