package edu.missouristate.mars.plugin.ui

import edu.missouristate.mars.util.boxedClassOf
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.table.DefaultTableCellRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ColumnsTest {
  private data class Row(val address: Int, val text: String?)

  @Test
  fun `plain columns retain nullable values and native read only defaults`() {
    val column = columnInfo<Row, String>("Source") { it.text }
    val row = Row(8, null)
    assertEquals("Source", column.name)
    assertNull(column.valueOf(row))
    assertEquals("line", column.valueOf(Row(4, "line")))
    assertNull(column.comparator)
    assertFalse(column.isSortable)
    assertEquals(boxedClassOf<String>(), column.columnClass)
    assertFalse(column.isCellEditable(row))
    column.setValue(row, "ignored")
    assertNull(row.text)
    assertNull(column.getRenderer(row))
    assertNull(column.getEditor(row))
    val renderer = DefaultTableCellRenderer()
    assertSame(renderer, column.getCustomizedRenderer(row, renderer))
  }

  @Test
  fun `configured columns snapshot order and class without wrapping values`() {
    val order = compareBy<Row> { it.address }
    lateinit var retained: ColumnInfoBuilder<Row>
    val icon = ImageIcon()
    val column =
        buildColumnInfo<Row, Icon>("Breakpoint", { icon }) {
          retained = this
          comparator(order)
          columnClass<Icon>()
        }
    retained.comparator(order.reversed())
    retained.columnClass<String>()
    assertSame(order, column.comparator)
    assertTrue(column.isSortable)
    assertTrue(column.comparator!!.compare(Row(4, null), Row(8, null)) < 0)
    assertSame(icon, column.valueOf(Row(4, null)))
    assertEquals(boxedClassOf<Icon>(), column.columnClass)
  }
}
