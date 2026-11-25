package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.JBUI
import edu.missouristate.mars.util.javaClassOf
import java.lang.reflect.Proxy
import javax.swing.JPanel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsLearningPresentationTest {
  @Test
  fun `a narrow side dock expands by the missing width and wider user layouts are preserved`() {
    val target = JBUI.scale(500)
    for (anchor in listOf(ToolWindowAnchor.LEFT, ToolWindowAnchor.RIGHT)) {
      for (width in listOf(1, target - 1, target, target + 200, 0)) {
        val (window, stretches) = window(width, anchor)
        MarsLearningPresentation.fit(window)
        assertEquals(
            if (width in 1 until target) listOf(target - width) else emptyList(),
            stretches,
        )
      }
    }
  }

  @Test
  fun `hidden floating horizontal and unsupported windows do not change layout`() {
    MarsLearningPresentation.fit(null)
    val unsupported =
        Proxy.newProxyInstance(
            javaClassOf<ToolWindow>().classLoader,
            arrayOf(javaClassOf<ToolWindow>()),
        ) { _, _, _ ->
          error("Unsupported window must not be inspected")
        } as ToolWindow
    MarsLearningPresentation.fit(unsupported)
    for ((anchor, type, visible) in
        listOf(
            Triple(ToolWindowAnchor.LEFT, ToolWindowType.DOCKED, false),
            Triple(ToolWindowAnchor.TOP, ToolWindowType.DOCKED, true),
            Triple(ToolWindowAnchor.BOTTOM, ToolWindowType.DOCKED, true),
            Triple(ToolWindowAnchor.LEFT, ToolWindowType.FLOATING, true),
            Triple(ToolWindowAnchor.RIGHT, ToolWindowType.SLIDING, true),
        )) {
      val (window, stretches) = window(100, anchor, type, visible)
      MarsLearningPresentation.fit(window)
      assertTrue(stretches.isEmpty())
    }
  }

  private fun window(
      width: Int,
      anchor: ToolWindowAnchor,
      type: ToolWindowType = ToolWindowType.DOCKED,
      visible: Boolean = true,
  ): Pair<ToolWindowEx, List<Int>> {
    val component = JPanel().apply { setSize(width, 800) }
    val stretches = mutableListOf<Int>()
    val window =
        Proxy.newProxyInstance(
            javaClassOf<ToolWindowEx>().classLoader,
            arrayOf(javaClassOf<ToolWindowEx>()),
        ) { _, method, args ->
          when (method.name) {
            "getComponent" -> component
            "getAnchor" -> anchor
            "getType" -> type
            "isVisible" -> visible
            "stretchWidth" -> {
              stretches.add(args[0] as Int)
              null
            }
            else -> error("Unexpected window access: ${method.name}")
          }
        } as ToolWindowEx
    return window to stretches
  }
}
