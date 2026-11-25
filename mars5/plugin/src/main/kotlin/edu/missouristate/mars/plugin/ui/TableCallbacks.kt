package edu.missouristate.mars.plugin.ui

import com.intellij.ui.ColoredTableCellRenderer
import javax.swing.JTable
import javax.swing.table.TableModel
import javax.swing.table.TableStringConverter

internal fun tableStringConverter(
    convert: (TableModel, Int, Int) -> String,
): TableStringConverter =
    object : TableStringConverter() {
      override fun toString(model: TableModel, row: Int, column: Int): String =
          convert(model, row, column)
    }

/** Native renderer setup and clearing precede this callback, as with a direct subclass. */
internal fun coloredTableCellRenderer(
    customize: ColoredTableCellRenderer.(JTable, Any?, Boolean, Boolean, Int, Int) -> Unit,
): ColoredTableCellRenderer =
    object : ColoredTableCellRenderer() {
      override fun customizeCellRenderer(
          table: JTable,
          value: Any?,
          selected: Boolean,
          hasFocus: Boolean,
          row: Int,
          column: Int,
      ) = customize(table, value, selected, hasFocus, row, column)
    }
