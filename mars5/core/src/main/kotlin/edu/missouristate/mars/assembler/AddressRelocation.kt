package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.util.Binary

internal enum class AddressPart(val spelling: String, private val shift: Int, private val carry: Long) {
    LOW("%lo", 0, 0), HIGH("%hi", 16, 0x8000), HIGHER("%higher", 32, 0x8000_8000), HIGHEST("%highest", 48, 0x8000_8000_8000);

    fun field(address: Long): Int = ((address + carry) ushr shift).toInt() and 0xffff

    companion object {
        fun parse(spelling: String): AddressPart? = entries.firstOrNull { it.spelling == spelling }
    }
}

/** A symbol plus an optional addend, folded to a machine field after symbol resolution. */
internal class AddressRelocation(partToken: Token, val symbol: Token, val part: AddressPart, val addend: Long) : Token(
    TokenTypes.ADDRESS_RELOCATION, partToken.value, partToken.sourceMIPSprogram, partToken.sourceLine, partToken.startPos,
) {
    init {
        setDiagnosticOrigin(partToken)
        setExpansionSource(partToken.writtenOrigin())
    }
}

/** Collapse each relocation expression to a single operand before matching instruction forms. */
internal fun parseAddressRelocations(tokens: TokenList, errors: ErrorList): Boolean {
    var index = 1
    while (index < tokens.size()) {
        val head = tokens[index]
        val part = if (head.value.startsWith('%')) AddressPart.parse(head.value) else null
        if (part == null) {
            index++
            continue
        }
        fun tokenAt(position: Int) = if (position < tokens.size()) tokens[position] else null
        val open = tokenAt(index + 1)
        val symbol = tokenAt(index + 2)
        if (open?.type != TokenTypes.LEFT_PAREN || symbol == null ||
            (symbol.type != TokenTypes.IDENTIFIER && symbol.type != TokenTypes.OPERATOR)) {
            errors.add(ErrorMessage(head, "${part.spelling} requires a label in parentheses"))
            return false
        }
        var closing = index + 3
        var addend = 0L
        val sign = tokenAt(closing)
        if (sign?.type == TokenTypes.PLUS || sign?.type == TokenTypes.MINUS) {
            val number = tokenAt(closing + 1)
            val value = number?.let { runCatching { Binary.stringToDoubleword(it.value) }.getOrNull() }
            if (value == null) {
                errors.add(ErrorMessage(sign, "${part.spelling} requires an integer addend"))
                return false
            }
            addend = if (sign.type == TokenTypes.MINUS) -value else value
            closing += 2
        }
        if (tokenAt(closing)?.type != TokenTypes.RIGHT_PAREN) {
            errors.add(ErrorMessage(head, "${part.spelling} requires a closing parenthesis"))
            return false
        }
        tokens[index] = AddressRelocation(head, symbol, part, addend)
        repeat(closing - index) { tokens.remove(index + 1) }
        index++
    }
    return true
}
