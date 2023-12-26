/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.assembler

import edu.missouristate.mars.*
import java.io.File

/**
 * A tokenizer is capable of tokenizing a complete MIPS program, or a given line from
 * a MIPS program. Since MIPS is line-oriented, each line defines a complete statement.
 * Tokenizing is the process of analyzing the input MIPS program for the purpose of
 * recognizing each MIPS language element. The types of language elements are known as "tokens".
 * MIPS tokens are defined in the TokenTypes class.
 * Example:
 * The MIPS statement `here:  lw  $t3, 8($t4)  # Load third member of array`
 * generates the following token list:
 * IDENTIFIER, COLON, OPERATOR, REGISTER_NAME, COMMA, INTEGER_5, LEFT_PAREN,
 * REGISTER_NAME, RIGHT_PAREN, COMMENT
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class Tokenizer @JvmOverloads constructor(
    private var sourceMipsProgram: MIPSProgram? = null
) {
    companion object {
        const val escapedCharacters = "'\"\\ntbrf0"
        @JvmStatic val escapedCharactersValues = arrayOf("39", "34", "92", "10", "9", "8", "13", "12", "0")
    }

    var errors: ErrorList = ErrorList()
        private set
    private lateinit var equivalents: HashMap<String, String>

    /**
     * Will tokenize a complete MIPS program.  MIPS is line oriented (not free format),
     * so we will be line-oriented too.
     *
     * @param p The MIPSProgram to be tokenized.
     * @return An ArrayList representing the tokenized program.  Each list member is a TokenList
     * that represents a tokenized source statement from the MIPS program.
     */
    @Throws(ProcessingException::class)
    fun tokenize(p: MIPSProgram): ArrayList<TokenList> {
        sourceMipsProgram = p
        equivalents = hashMapOf()
        val tokenList = arrayListOf<TokenList>()
        val source = processIncludes(p, hashMapOf())
        p.setSourceLineList(source)
        var currentLineTokens: TokenList
        var sourceLine: String
        for (i in source.indices) {
            sourceLine = source[i].source
            currentLineTokens = tokenizeLine(i + 1, sourceLine)
            tokenList.add(currentLineTokens)
            // If source code substitution was made
            // based on .eqv directive during tokenizing, the processed line, a String, is
            // not the same object as the original line.  Thus I can use != instead of !equals()
            // This IF statement will replace original source with source modified by .eqv substitution.
            // Not needed by assembler, but looks better in the Text Segment Display.
            if (sourceLine.isNotEmpty() && sourceLine != currentLineTokens.processedLine)
                source[i] = SourceLine(currentLineTokens.processedLine, source[i].mipsProgram, source[i].lineNumber)
        }
        if (errors.hasErrors) throw ProcessingException(errors)
        return tokenList
    }

    /**
     * Pre-pre-processing pass through source code to process any ".include" directives.
     * When one is encountered, the contents of the included file are inserted at that
     * point. If no .include statements, the return value is a new array list but
     * with the same lines of source code. Uses recursion to correctly process included
     * files that themselves have .include. Plus, it will detect and report recursive
     * includes both direct and indirect.
     * DPS 11-Jan-2013
     */
    @Throws(ProcessingException::class)
    private fun processIncludes(program: MIPSProgram, inclFiles: MutableMap<String, String>): ArrayList<SourceLine> {
        val source = program.getSourceList()
        val result = ArrayList<SourceLine>(source.size)
        for (i in source.indices) {
            val line = source[i]
            val tl = tokenizeLine(program, i + 1, line, false)
            var hasInclude = false
            for (j in tl.indices) {
                if (tl[j].value.equals(Directives.INCLUDE.name, true) &&
                    tl.size > j + 1 &&
                    tl[j + 1].type == TokenTypes.QUOTED_STRING) {
                    var filename = tl[j + 1].value
                    // Get rid of quotes
                    filename = filename.substring(1, filename.length - 1)
                    // Handle either absolute or relative pathname for .include file
                    if (!File(filename).isAbsolute)
                        filename = File(program.getFilename()).parent + File.separator + filename
                    if (inclFiles.containsKey(filename)) {
                        // This is a recursive inclusion. Generate an error message and return immediately.
                        val t = tl[j + 1]
                        errors.add(ErrorMessage(
                            program,
                            t.sourceLine,
                            t.startPosition,
                            "Recursive include of file $filename!"
                        ))
                        throw ProcessingException(errors)
                    }
                    inclFiles[filename] = filename
                    val incl = MIPSProgram()
                    try {
                        incl.readSource(filename)
                    } catch (p: ProcessingException) {
                        val t = tl[j + 1]
                        errors.add(ErrorMessage(
                            program,
                            t.sourceLine,
                            t.startPosition,
                            "Error reading include file $filename!"
                        ))
                        throw ProcessingException(errors)
                    }
                    val allLines = processIncludes(incl, inclFiles)
                    result.addAll(allLines)
                    hasInclude = true
                    break
                }
            }
            if (!hasInclude)
                result.add(SourceLine(line, program, i + 1))
        }
        return result
    }

    /**
     * Used only to create a token list for the example provided with each instruction
     * specification.
     *
     * @param example The example MIPS instruction to be tokenized.
     * @return A TokenList representing the tokenized instruction.  Each list member is a Token
     * that represents one language element.
     * @throws ProcessingException This occurs only if the instruction specification itself
     *                             contains one or more lexical (i.e., token) errors.
     */
    @Throws(ProcessingException::class)
    fun tokenizeExampleInstruction(example: String): TokenList {
        val result = tokenizeLine(sourceMipsProgram, 0, example, false)
        if (errors.hasErrors) throw ProcessingException(errors)
        return result
    }

    /*
     * Tokenizing is not as easy as it appears at first blush, because the typical
     * delimiters: space, tab, comma, can all appear inside MIPS quoted ASCII strings!
     * Also, spaces are not as necessary as they seem, the following line is accepted
     * and parsed correctly by SPIM: label:lw,$t4,simple#comment
     * as is this weird variation: label  :lw  $t4  ,simple ,  ,  , # comment
     *
     * as is this line:  stuff:.asciiz"# ,\n\"","aaaaa"  (interestingly, if you put
     * additional characters after the \", they are ignored!!)
     *
     * I also would like to know the starting character position in the line of each
     * token, for error reporting purposes.  StringTokenizer cannot give you this.
     *
     * Given all the above, it is just as easy to "roll my own" as to use StringTokenizer
     */

    /**
     * Will tokenize one line of source code.  If lexical errors are discovered,
     * they are noted in an ErrorMessage object which is added to the ErrorList.
     * Will NOT throw an exception yet because we want to persevere beyond first error.
     *
     * @param lineNum line number from source code (used in error message)
     * @param theLine String containing source code
     * @return the generated token list for that line
     */
    fun tokenizeLine(lineNum: Int, theLine: String) =
        tokenizeLine(sourceMipsProgram, lineNum, theLine, true)

    /**
     * Will tokenize one line of source code.  If lexical errors are discovered,
     * they are noted in an ErrorMessage object which is added to the provided ErrorList
     * instead of the Tokenizer's error list. Will NOT throw an exception.
     *
     * @param lineNum         line number from source code (used in error message)
     * @param theLine         String containing source code
     * @param callerErrorList errors will go into this list instead of tokenizer's list.
     * @return the generated token list for that line
     */
    fun tokenizeLine(lineNum: Int, theLine: String, callerErrorList: ErrorList): TokenList {
        val saveList = errors
        errors = callerErrorList
        val tokens = tokenizeLine(lineNum, theLine)
        errors = saveList
        return tokens
    }

    /**
     * Will tokenize one line of source code.  If lexical errors are discovered,
     * they are noted in an ErrorMessage object which is added to the provided ErrorList
     * instead of the Tokenizer's error list. Will NOT throw an exception.
     *
     * @param lineNum          line number from source code (used in error message)
     * @param theLine          String containing source code
     * @param callerErrorList  errors will go into this list instead of tokenizer's list.
     * @param doEqvSubstitutes boolean param set true to perform .eqv substitutions, else false
     * @return the generated token list for that line
     */
    fun tokenizeLine(lineNum: Int, theLine: String, callerErrorList: ErrorList, doEqvSubstitutes: Boolean): TokenList {
        val saveList = errors
        errors = callerErrorList
        val tokens = tokenizeLine(sourceMipsProgram, lineNum, theLine, doEqvSubstitutes)
        errors = saveList
        return tokens
    }

    /**
     * Will tokenize one line of source code.  If lexical errors are discovered,
     * they are noted in an ErrorMessage object which is added to the provided ErrorList
     * instead of the Tokenizers error list. Will NOT throw an exception.
     *
     * @param program          MIPSProgram containing this line of source
     * @param lineNum          line number from source code (used in error message)
     * @param theLine          String containing source code
     * @param doEqvSubstitutes boolean param set true to perform .eqv substitutions, else false
     * @return the generated token list for that line
     */
    fun tokenizeLine(program: MIPSProgram?, lineNum: Int, theLine: String, doEqvSubstitutes: Boolean): TokenList {
        var result = TokenList()
        if (theLine.isEmpty()) return result
        // Will be faster to work with char arrays instead of strings
        var c: Char
        val line = theLine.toCharArray()
        var linePos = 0
        val token = CharArray(line.size)
        var tokenPos = 0
        var tokenStartPos = 1
        var insideQuotedString = false
        if (Globals.debug) println("Source line: $theLine")
        while (linePos < line.size) {
            c = line[linePos]
            if (insideQuotedString) {
                // Everything goes into the token
                token[tokenPos++] = c
                if (c == '"' && token[tokenPos - 2] != '\\') {
                    // If quote is not preceded by a backslash, this is the end
                    processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                    tokenPos = 0
                    insideQuotedString = false
                }
            } else {
                // Not inside a quoted string, so be sensitive to delimiters
                when (c) {
                    // # denotes a comment that takes the remainder of the line
                    '#' -> {
                        if (tokenPos > 0) processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                        tokenStartPos = linePos + 1
                        tokenPos = line.size - linePos
                        System.arraycopy(line, linePos, token, 0, tokenPos)
                        processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                        linePos = line.size
                        tokenPos = 0
                    }
                    // Space, tab, or comma is delimiter
                    ' ', '\t', ',' -> if (tokenPos > 0) {
                        processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                        tokenPos = 0
                    }
                    // These two guys are special.  Will be recognized as unary if and only if two conditions hold:
                    // 1. Immediately followed by a digit (will use look-ahead for this).
                    // 2. The previous token, if any, is _not_ an IDENTIFIER
                    // Otherwise considered binary and thus a separate token.  This is a slight hack but reasonable.
                    '+', '-' -> {
                        // Here's the REAL hack: recognizing signed exponent in E-notation floating point!
                        // (e.g., 1.2e-5) Add the plus or minus to the token and keep going.
                        if (tokenPos > 0 && line.size >= linePos + 2 && Character.isDigit(line[linePos + 1]) &&
                            (line[linePos - 1] == 'e' || line[linePos - 1] == 'E')) {
                            token[tokenPos++] = c
                            break
                        }
                        // End of REAL hack.
                        if (tokenPos > 0) {
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                        }
                        tokenStartPos = linePos + 1
                        token[tokenPos++] = c
                        if (!((result.isEmpty() || result[result.size - 1].type != TokenTypes.IDENTIFIER) &&
                                    (line.size >= linePos + 2 && Character.isDigit(line[linePos + 1])))) {
                            // Treat it as a binary.
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                        }
                    }
                    // These are other single-character tokens
                    ':', '(', ')' -> {
                        if (tokenPos > 0) {
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                        }
                        tokenStartPos = linePos + 1
                        token[tokenPos++] = c
                        processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                        tokenPos = 0
                    }
                    // We're not inside a quoted string, so start a new token.
                    '"' -> {
                        if (tokenPos > 0) {
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                        }
                        // Our strategy is to process the whole thing right now.
                        tokenStartPos = linePos + 1
                        // Put the quote in token[0]
                        token[tokenPos++] = c
                        val lookaheadChars = line.size - linePos - 1
                        // We need a minimum of 2 more characters: 1 for the character, and 1 for the ending quote
                        // This is an error if it doesn't work out.
                        if (lookaheadChars < 2) break
                        c = line[++linePos]
                        // Grab a second character, put it in token[1]
                        token[tokenPos++] = c
                        // This will be an error; there's nothing between the quotes
                        if (c == '\'') break
                        c = line[++linePos]
                        // Grab the third character, put it in token[2]
                        token[tokenPos++] = c
                        // Process if we've either reached second, non-escaped, quote, or end of the line.
                        if (c == '\'' && token[1] != '\\' || lookaheadChars == 2) {
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                            tokenStartPos = linePos + 1
                            break
                        }
                        // At this point, there is at least one more character on this line. If we're
                        // still here after seeing a second quote, it was escaped.  Not done yet;
                        // we either have an escape code, an octal code (also escaped) or invalid.
                        c = line[++linePos]
                        // Grab the fourth character, put it in token[3]
                        token[tokenPos++] = c
                        // Process if this is the ending quote for escaped character or if at the end of the line
                        if (c == '\'' || lookaheadChars == 3) {
                            processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                            tokenPos = 0
                            tokenStartPos = linePos + 1
                            break
                        }
                        // At this point, we've handled all legal possibilities except octal, e.g., '\377'
                        // Proceed, if enough characters remain to finish off octal.
                        if (lookaheadChars >= 5) {
                            c = line[++linePos]
                            // Grab the fifth character, put it in token[4]
                            token[tokenPos++] = c
                            if (c != '\'') {
                                // Still haven't reached the end, last chance for validity!
                                c = line[++linePos]
                                // Grab the sixth character, put it in token[5]
                                token[tokenPos++] = c
                            }
                        }
                        // Process no matter what. We either have a valid character by now, or we don't.
                        processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
                        tokenPos = 0
                        tokenStartPos = linePos + 1
                    }
                    else -> {
                        if (tokenPos == 0) tokenStartPos = linePos + 1
                        token[tokenPos++] = c
                    }
                }
            }
            linePos++
        }
        if (tokenPos > 0) processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result)
        if (doEqvSubstitutes) result = processEqv(program, lineNum, theLine, result)
        return result
    }

    /**
     * Process the .eqv directive, which needs to be applied prior to tokenizing of subsequent statements.
     * This handles detecting that theLine contains a .eqv directive, in which case it needs
     * to be added to the HashMap of equivalents.  It also handles detecting that theLine
     * contains a symbol that was previously defined in an .eqv directive, in which case
     * the substitution needs to be made.
     * DPS 11-July-2012
     */
    private fun processEqv(program: MIPSProgram?, lineNum: Int, theLine: String, tokens: TokenList): TokenList {
        var theLine = theLine
        // See if it is .eqv directive.  If so, record it...
        // We have to determine if it is a well-formed statement right now (can't wait for assembler).
        if (tokens.size > 2 && (tokens[0].type == TokenTypes.DIRECTIVE || tokens[2].type == TokenTypes.DIRECTIVE)) {
            // There should not be a label.
            // But if there is, the directive is in token position 2 (ident, colon, directive).
            val dirPos = if (tokens[0].type == TokenTypes.DIRECTIVE) 0 else 2
            if (Directives.matchDirective(tokens[dirPos].value) == Directives.EQV) {
                // Get position in the token list of the last non-comment token
                val tokenPosLastOperand = tokens.size - (if (tokens.last().type == TokenTypes.COMMENT) 2 else 1)
                // There have to be at least two non-comment tokens beyond the directive.
                if (tokenPosLastOperand < dirPos + 2) {
                    errors.add(ErrorMessage(
                        program,
                        lineNum,
                        tokens[dirPos].startPosition,
                        "Too few operands for .eqv directive!"
                    ))
                    return tokens
                }
                // The token following the directive has to be an IDENTIFIER
                if (tokens[dirPos + 1].type != TokenTypes.IDENTIFIER) {
                    errors.add(ErrorMessage(
                        program,
                        lineNum,
                        tokens[dirPos].startPosition,
                        "Malformed .eqv directive!"
                    ))
                    return tokens
                }
                val symbol = tokens[dirPos + 1].value
                // Make sure the symbol is not contained in the expression.
                // Unlikely to occur, but if left undetected, it will result in infinite recursion.
                // For example, `.eqv ONE, (ONE)` might trigger this.
                for (i in (dirPos + 2)..<tokens.size) {
                    if (tokens[i].value == symbol) {
                        errors.add(ErrorMessage(
                            program,
                            lineNum,
                            tokens[dirPos].startPosition,
                            "Cannot substitute $symbol for itself in .eqv directive!"
                        ))
                        return tokens
                    }
                }
                // Expected syntax is symbol, expression. I'm allowing the expression to comprise
                // multiple tokens, so I want to get everything from the IDENTIFIER to either the
                // COMMENT or to the end.
                val startExpression = tokens[dirPos + 2].startPosition
                val endExpression = tokens[tokenPosLastOperand].startPosition + tokens[tokenPosLastOperand].value.length
                val expression = theLine.substring(startExpression - 1, endExpression - 1)
                // Symbol cannot be redefined;
                // the only reason for this is to act like the GNU assembler's version of .eqv
                if (equivalents.containsKey(symbol) && equivalents[symbol] != expression) {
                    errors.add(ErrorMessage(
                        program,
                        lineNum,
                        tokens[dirPos + 1].startPosition,
                        "\"$symbol\" is already defined!"
                    ))
                    return tokens
                }
                equivalents[symbol] = expression
                return tokens
            }
        }
        // Check if a substitution from defined `.eqv` is to be made. If so, make one.
        var substitutionMade = false
        for (i in 0..<tokens.size) {
            val token = tokens[i]
            if (token.type == TokenTypes.IDENTIFIER && equivalents.containsKey(token.value)) {
                // Do the substitution
                val sub = equivalents[token.value]
                val startPos = token.startPosition
                theLine = theLine.substring(0, startPos - 1) + sub + theLine.substring(startPos + token.value.length - 1)
                substitutionMade = true
                break
            }
        }
        tokens.processedLine = theLine
        return if (substitutionMade) tokenizeLine(lineNum, theLine) else tokens
    }

    /**
     * Given a candidate token and its position, classify and record it.
     */
    private fun processCandidateToken(token: CharArray, program: MIPSProgram?, line: Int, theLine: String, tokenPos: Int, tokenStartPos: Int, tokenList: TokenList) {
        var value = String(token, 0, tokenPos)
        if (value.isNotEmpty() && value[0] == '\'') value = preprocessCharacterLiteral(value)
        val type = TokenTypes.matchTokenType(value) ?: TokenTypes.ERROR
        if (type == TokenTypes.ERROR)
            errors.add(ErrorMessage(program, line, tokenStartPos, "$theLine\nInvalid language element: $value"))
        tokenList.add(Token(type, value, program, line, tokenStartPos))
    }

    /**
     * If passed a candidate character literal, attempt to translate it into integer constant.
     * If the translation fails, return the original value.
     */
    private fun preprocessCharacterLiteral(value: String): String {
        // Must start and end with a quote and have something in between the quotes
        if (value.length < 3 || value[0] != '\'' || value[value.lastIndex] != '\'') return value
        val quotesRemoved = value.substring(1, value.lastIndex)
        // If not escaped and one character is left, return it's value; otherwise return the original.
        if (quotesRemoved[0] != '\\') return if (quotesRemoved.length == 1) quotesRemoved[0].code.toString() else value
        // Now we know it is an escape sequence and have to decode which of the 8: ', ", \, n, t, b, r, or f
        if (quotesRemoved.length == 2) {
            val escapedCharacterIndex = escapedCharacters.indexOf(quotesRemoved[1])
            return if (escapedCharacterIndex >= 0) escapedCharactersValues[escapedCharacterIndex] else value
        }
        // The last valid possibility is a 3-digit octal code, in range from 000 to 377
        if (quotesRemoved.length == 4) {
            try {
                val intValue = Integer.parseInt(quotesRemoved.substring(1), 8)
                if (intValue in 0..255) return intValue.toString()
            } catch (ignored: NumberFormatException) {}
            // Invalid octal numbers will fall through the exception handler.
        }
        return value
    }
}