package edu.missouristate.mars.plugin.ui

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.debug.modificationCallback
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class TableCallbacksTest {
  @Test
  fun `renderer retains native clearing and forwards nullable values and cell state`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val table = JTable(2, 2)
          val calls = mutableListOf<List<Any?>>()
          val thread = Thread.currentThread()
          val renderer = coloredTableCellRenderer { actual, value, selected, focus, row, column ->
            assertSame(table, actual)
            assertSame(thread, Thread.currentThread())
            calls += listOf(value, selected, focus, row, column)
            append(value?.toString() ?: "empty")
          }
          assertSame(
              renderer,
              renderer.getTableCellRendererComponent(table, "first", true, true, 1, 0),
          )
          assertEquals("first", renderer.toString())
          renderer.getTableCellRendererComponent(table, null, false, false, 0, 1)
          assertEquals("empty", renderer.toString())
          assertEquals(
              listOf(listOf("first", true, true, 1, 0), listOf(null, false, false, 0, 1)),
              calls,
          )
        }
      }

  @Test
  fun `converter keeps model coordinates and callback failures propagate unchanged`() {
    val model = DefaultTableModel(arrayOf(arrayOf<Any>("a", "b")), arrayOf("left", "right"))
    val converter = tableStringConverter { actual, row, column ->
      assertSame(model, actual)
      actual.getValueAt(row, column).toString()
    }
    assertEquals("b", converter.toString(model, 0, 1))
    val failure = IllegalStateException("callback")
    val outcomes = mutableListOf<String>()
    val callback = modificationCallback({ outcomes += "success" }, { outcomes += it })
    callback.valueModified()
    callback.errorOccurred("rejected")
    assertEquals(listOf("success", "rejected"), outcomes)
    val throwing = modificationCallback({ throw failure }, { throw failure })
    assertSame(failure, assertThrows<IllegalStateException> { throwing.valueModified() })
    assertSame(failure, assertThrows<IllegalStateException> { throwing.errorOccurred("error") })
    val throwingConverter = tableStringConverter { _, _, _ -> throw failure }
    assertSame(
        failure,
        assertThrows<IllegalStateException> { throwingConverter.toString(model, 0, 0) },
    )
  }
}
