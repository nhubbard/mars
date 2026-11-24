package edu.missouristate.mars.assembler

/** Repeated decimal labels are bound in source order, independently of addresses. */
internal class LocalNumericLabels {
  internal class Slot(var symbol: Symbol? = null)

  private class Occurrences(var previous: Slot? = null, var next: Slot? = null)

  private val labels = HashMap<ULong, Occurrences>()
  private val definitions = ArrayList<Symbol>()

  fun define(number: ULong, symbol: Symbol) {
    val occurrences = labels.getOrPut(number, ::Occurrences)
    occurrences.previous = (occurrences.next ?: Slot()).also { it.symbol = symbol }
    occurrences.next = null
    definitions.add(symbol)
  }

  fun align(original: Long, aligned: Long) {
    for (symbol in definitions) if (symbol.addressBits == original) symbol.addressBits = aligned
  }

  fun reference(token: Token): LocalNumericReference? {
    val number = referenceNumber(token.value) ?: return null
    val occurrences = labels.getOrPut(number, ::Occurrences)
    val slot =
        if (token.value.last() == 'b') occurrences.previous ?: Slot()
        else {
          occurrences.next ?: Slot().also { occurrences.next = it }
        }
    return LocalNumericReference(token, slot)
  }

  companion object {
    fun decimal(value: String): ULong? =
        value.takeIf { it.all { character -> character in '0'..'9' } }?.toULongOrNull()

    fun referenceNumber(spelling: String): ULong? =
        when (spelling.lastOrNull()) {
          'b',
          'f' -> decimal(spelling.dropLast(1))
          else -> null
        }
  }
}

internal class LocalNumericReference(written: Token, private val slot: LocalNumericLabels.Slot) :
    Token(
        TokenTypes.IDENTIFIER,
        written.value,
        written.sourceMIPSprogram,
        written.sourceLine,
        written.startPos,
    ) {
  val symbol
    get() = slot.symbol

  init {
    setDiagnosticOrigin(written)
    setExpansionSource(written.writtenOrigin())
    setEquivalenceSource(written.equivalenceSource())
  }
}
