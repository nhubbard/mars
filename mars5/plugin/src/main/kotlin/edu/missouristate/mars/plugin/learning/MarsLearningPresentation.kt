package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.util.ui.JBUI

/**
 * Fit the native host's minimum text width without replacing or inspecting its lesson components.
 */
internal object MarsLearningPresentation {
  fun fit(window: ToolWindow?) {
    if (
        window !is ToolWindowEx ||
            !window.isVisible ||
            window.anchor.isHorizontal ||
            window.type != ToolWindowType.DOCKED
    )
        return
    val width = window.component.width
    val missing = JBUI.scale(500) - width
    // A zero width means layout has not run. Floating and user-widened panes are left alone.
    if (width > 0 && missing > 0) window.stretchWidth(missing)
  }
}
