package edu.missouristate.mars.plugin.ui

import com.intellij.openapi.util.NlsContexts.ColumnName
import com.intellij.util.ui.ColumnInfo
import edu.missouristate.mars.util.boxedClassOf

/**
 * Only the options used by MARS columns; editing and cell renderers remain native table concerns.
 */
internal class ColumnInfoBuilder<Row> internal constructor() {
  private var order: Comparator<Row>? = null
  private var type: Class<*> = boxedClassOf<String>()

  fun comparator(comparator: Comparator<Row>) {
    order = comparator
  }

  inline fun <reified Value : Any> columnClass() = columnClass(boxedClassOf<Value>())

  fun columnClass(type: Class<*>) {
    this.type = type
  }

  internal fun <Value> build(
      name: @ColumnName String,
      value: (Row) -> Value?,
  ): ColumnInfo<Row, Value> {
    val comparator = order
    val columnClass = type
    return object : ColumnInfo<Row, Value>(name) {
      override fun valueOf(item: Row): Value? = value(item)

      override fun getComparator(): Comparator<Row>? = comparator

      override fun getColumnClass(): Class<*> = columnClass
    }
  }
}

internal fun <Row, Value> columnInfo(
    name: @ColumnName String,
    value: (Row) -> Value?,
): ColumnInfo<Row, Value> = buildColumnInfo(name, value) {}

/** Separate name avoids ambiguous Function1 overloads for value and configuration lambdas. */
internal fun <Row, Value> buildColumnInfo(
    name: @ColumnName String,
    value: (Row) -> Value?,
    configure: ColumnInfoBuilder<Row>.() -> Unit,
): ColumnInfo<Row, Value> = ColumnInfoBuilder<Row>().apply(configure).build(name, value)
