package edu.missouristate.mars.plugin.platform

import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.containers.JBIterable
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import edu.missouristate.mars.plugin.debug.MarsDebugProcess
import edu.missouristate.mars.plugin.debug.getDebugProcesses
import edu.missouristate.mars.plugin.dynamicBundleOf
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.findChildrenOfType
import edu.missouristate.mars.plugin.language.psi.psiElement
import edu.missouristate.mars.plugin.run.MarsRunConfigurationProducer
import edu.missouristate.mars.plugin.run.runConfigurationProducer
import edu.missouristate.mars.plugin.ui.findComponentOfType
import edu.missouristate.mars.plugin.ui.findComponentsOfType
import edu.missouristate.mars.util.javaClassOf
import java.util.EventListener
import javax.swing.JLabel
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class InteropPlatformTest {
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  fun interface ProbeListener : EventListener {
    fun changed(value: Int)
  }

  @Test
  fun `event dispatch keeps order listener identity and parent disposal`() {
    val dispatcher = eventDispatcherOf<ProbeListener>()
    val lifetime = Disposer.newDisposable()
    val calls = mutableListOf<String>()
    val first = ProbeListener { calls += "first $it" }
    val second = ProbeListener { calls += "second $it" }
    try {
      dispatcher.addListener(first, lifetime)
      dispatcher.addListener(second)
      dispatcher.multicaster.changed(1)
      Disposer.dispose(lifetime)
      dispatcher.multicaster.changed(2)
      dispatcher.removeListener(second)
      dispatcher.multicaster.changed(3)
      assertEquals(listOf("first 1", "second 1", "second 2"), calls)
    } finally {
      Disposer.dispose(lifetime)
    }
  }

  @Test
  fun `component search keeps native root inclusion order and null handling`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val root = JPanel()
          val nested = JPanel()
          val first = JLabel("first")
          val second = JLabel("second")
          root.add(first)
          root.add(nested)
          nested.add(second)
          assertEquals(listOf(first, second), findComponentsOfType<JLabel>(root))
          assertEquals(
              UIUtil.findComponentsOfType(root, javaClassOf<JPanel>()),
              findComponentsOfType<JPanel>(root),
          )
          assertTrue(findComponentsOfType<JLabel>(null).isEmpty())
          assertNull(findComponentOfType<JLabel>(null))
          assertSame(first, findComponentOfType<JLabel>(root))
          assertSame(root, findComponentOfType<JPanel>(root))
          assertEquals(listOf(root, first, nested, second), UIUtil.uiTraverser(root).toList())
          assertEquals(
              listOf(first, second),
              UIUtil.uiTraverser(root).filterIsInstance<JLabel>().toList(),
          )
        }
      }

  @Test
  fun `typed iterable filtering remains lazy and repeatable`() {
    var visits = 0
    val source =
        JBIterable.from(
            Iterable<Any> {
              visits++
              listOf("one", 2, "three").iterator()
            },
        )
    val strings = source.filterIsInstance<String>()
    assertEquals(0, visits)
    assertEquals(listOf("one", "three"), strings.toList())
    assertEquals(1, visits)
    assertEquals(listOf("one", "three"), strings.toList())
    assertEquals(2, visits)
    assertEquals(listOf(2), source.filterIsInstance<Int>().toList())
  }

  @Test
  fun `PSI search keeps recursive order root exclusion and native collection type`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val file = fixture.configureByText("labels.asm", ".text\nfirst: nop\nsecond: nop\n")
          val native = PsiTreeUtil.findChildrenOfType(file, javaClassOf<PsiElement>())
          val typed = findChildrenOfType<PsiElement>(file)
          assertEquals(native.javaClass, typed.javaClass)
          assertEquals(native.toList(), typed.toList())
          assertFalse(typed.any { it === file })
          assertTrue(findChildrenOfType<PsiElement>(null).isEmpty())
          val declaration = findChildrenOfType<MipsDeclaration>(file).first()
          assertTrue(psiElement<MipsDeclaration>().accepts(declaration))
          assertFalse(psiElement<MipsDeclaration>().accepts(file))
        }
      }

  @Test
  fun `typed lookups retain registered producer identity empty debug sessions and bundle owner`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          assertSame(
              RunConfigurationProducer.getInstance(javaClassOf<MarsRunConfigurationProducer>()),
              runConfigurationProducer<MarsRunConfigurationProducer>(),
          )
          assertTrue(
              XDebuggerManager.getInstance(projects.get())
                  .getDebugProcesses<MarsDebugProcess>()
                  .isEmpty(),
          )
          assertEquals(
              dynamicBundleOf<MipsFileType>("messages.MarsBundle")
                  .getMessage("filetype.description"),
              dynamicBundleOf<MipsFileType>().getMessage("filetype.description"),
          )
        }
      }
}
