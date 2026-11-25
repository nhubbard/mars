package edu.missouristate.mars.di

import edu.missouristate.mars.Settings
import edu.missouristate.mars.TestSessions.NO_GUI
import edu.missouristate.mars.api.MarsTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MarsComponentBuildTest {
  @Test
  fun `configuration binds the exact settings and frontend through Dagger`() {
    val settings = Settings()
    val component = MarsComponent.build {
      coreModule(CoreModule(settings, target = MarsTarget.MARS32))
      guiProxy(NO_GUI)
    }
    component.context().use { context ->
      assertSame(settings, component.settings())
      assertSame(settings.simulationSettings, context.settings)
      assertSame(NO_GUI, context.gui)
      assertEquals(MarsTarget.MARS32, context.target)
      assertSame(context, component.context())
    }
  }

  @Test
  fun `default modules and missing binding validation remain Dagger owned`() {
    val failure = assertThrows<IllegalStateException> { MarsComponent.build {} }
    assertTrue(failure.message.orEmpty().contains("MarsGuiProxy"))
    assertTrue(failure.message.orEmpty().contains("must be set"))
    MarsComponent.build { guiProxy(NO_GUI) }
        .context()
        .use {
          assertSame(NO_GUI, it.gui)
          assertEquals(MarsTarget.DEFAULT, it.target)
        }
  }

  @Test
  fun `each invocation creates independent registers memory and backstep state`() {
    val first = MarsComponent.build { guiProxy(NO_GUI) }
    val second = MarsComponent.build { guiProxy(NO_GUI) }
    first.context().use { a ->
      second.context().use { b ->
        assertNotSame(a, b)
        assertNotSame(a.memory, b.memory)
        assertNotSame(a.backStepper, b.backStepper)
        first.registerFile().setValue(8, 42)
        assertEquals(0, second.registerFile().getValue(8))
        assertEquals(42, first.registerFile().getValue(8))
      }
    }
  }

  @Test
  fun `configuration executes once and propagates the original failure`() {
    var calls = 0
    val error = IllegalArgumentException("configuration failed")
    val failure =
        assertThrows<IllegalArgumentException> {
          MarsComponent.build {
            calls++
            throw error
          }
        }
    assertSame(error, failure)
    assertEquals(1, calls)
  }
}
