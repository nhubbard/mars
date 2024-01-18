/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
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

@file:Suppress("NAME_SHADOWING", "KotlinConstantConditions")

package edu.missouristate.mars.venus.editor.marker

import edu.missouristate.mars.Globals
import edu.missouristate.mars.Globals.settings
import edu.missouristate.mars.CoreSettings
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.Directives.Companion.matchDirective
import edu.missouristate.mars.assembler.TokenTypes.Companion.isValidIdentifier
import edu.missouristate.mars.mips.hardware.Coprocessor1
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.treeSetOf
import edu.missouristate.mars.venus.editor.KeywordMap
import edu.missouristate.mars.venus.editor.PopupHelpItem
import edu.missouristate.mars.venus.editor.marker.Token.Type.*
import javax.swing.text.Segment

class MIPSTokenMarker @JvmOverloads constructor(
    private val keywords: KeywordMap = getKeywords()
) : TokenMarker() {
    companion object {
        @JvmStatic private val cKeywords: KeywordMap by lazy { getKeywords() }
        @JvmStatic val tokenLabels: HashMap<Token.Type, String> = hashMapOf(
            COMMENT1 to "Comment",
            LITERAL1 to "String literal",
            LITERAL2 to "Character literal",
            LABEL to "Label",
            KEYWORD1 to "MIPS instruction",
            KEYWORD2 to "Assembler directive",
            KEYWORD3 to "Register",
            INVALID to "In-progress, invalid",
            MACRO_ARG to "Macro parameter"
        )
        @JvmStatic val tokenExamples: HashMap<Token.Type, String> = hashMapOf(
            COMMENT1 to "# Load",
            LITERAL1 to "\"First\"",
            LITERAL2 to "'\\n'",
            LABEL to "main:",
            KEYWORD1 to "lui",
            KEYWORD2 to ".text",
            KEYWORD3 to "\$zero",
            INVALID to "\"Regi",
            MACRO_ARG to "%arg"
        )

        @JvmStatic
        private fun getKeywords(): KeywordMap {
            val result = KeywordMap(false)
            // Add instruction mnemonics
            val instructionSet = Globals.instructionSet.instructionList
            for (instruction in instructionSet)
                result.add(instruction.name, KEYWORD1)
            // Add assembler directives
            val directiveSet = Directives.getDirectiveList()
            for (directive in directiveSet)
                result.add(directive.name, Token.Type.KEYWORD2)
            // Add integer register file
            val registerFile = RegisterFile.registers
            for (i in registerFile.indices) {
                result.add(registerFile[i].name, Token.Type.KEYWORD3)
                result.add("$$i", Token.Type.KEYWORD3)
            }
            // Add Coprocessor 1 register file
            val fpuRegisterFile = Coprocessor1.registers
            for (register in fpuRegisterFile)
                result.add(register.name, Token.Type.KEYWORD3)
            return result
        }
    }

    private var lastOffset: Int = 0
    private var lastKeyword: Int = 0

    override fun markTokensImpl(type: Token.Type, line: Segment, lineIndex: Int): Token.Type {
        var type = type
        val array = line.array
        val offset = line.offset
        lastOffset = offset
        lastKeyword = offset
        val length = line.count + offset
        var backslash = false
        loop@ for (i in offset..<length) {
            val i1 = (i + 1)
            val c = array[i]
            if (c == '\\') {
                backslash = !backslash
                continue
            }
            when (type) {
                NULL -> when (c) {
                    '"' -> {
                        doKeyword(line, i)
                        if (backslash) backslash = false
                        else {
                            addToken(i - lastOffset, type)
                            type = LITERAL1
                            lastOffset = i
                            lastKeyword = i
                        }
                    }
                    '\'' -> {
                        doKeyword(line, i)
                        if (backslash) backslash = false
                        else {
                            addToken(i - lastOffset, type)
                            type = LITERAL2
                            lastOffset = i
                            lastKeyword = i
                        }
                    }
                    ':' -> {
                        backslash = false
                        val validIdentifier = try {
                            String(array, lastOffset, i1 - lastOffset - 1).trim().isValidIdentifier()
                        } catch (ignored: StringIndexOutOfBoundsException) { false }
                        if (validIdentifier) {
                            addToken(i1 - lastOffset, LABEL)
                            lastOffset = i1
                            lastKeyword = i1
                        }
                    }
                    '#' -> {
                        backslash = false
                        doKeyword(line, i)
                        if (length - i >= 1) {
                            addToken(i - lastOffset, type)
                            addToken(length - i, COMMENT1)
                            lastOffset = length
                            lastKeyword = length
                            break@loop
                        }
                    }
                    else -> {
                        backslash = false
                        if (!c.isLetterOrDigit() && c != '_' && c != '.' && c != '$' && c != '%')
                            doKeyword(line, i)
                    }
                }
                LITERAL1 -> {
                    if (backslash) backslash = false
                    else if (c == '"') {
                        addToken(i1 - lastOffset, type)
                        type = Token.Type.NULL
                        lastOffset = i1
                        lastKeyword = i1
                    }
                }
                LITERAL2 -> {
                    if (backslash) backslash = false
                    else if (c == '\'') {
                        addToken(i1 - lastOffset, LITERAL1)
                        type = NULL
                        lastOffset = i1
                        lastKeyword = i1
                    }
                }
                else -> throw InternalError("Invalid token type: $type")
            }
        }
        if (type == NULL) doKeyword(line, length)
        when (type) {
            LITERAL1, LITERAL2 -> {
                addToken(length - lastOffset, INVALID)
                type = NULL
            }
            KEYWORD2 -> {
                addToken(length - lastOffset, type)
                if (!backslash) type = NULL
            }
            else -> addToken(length - lastOffset, type)
        }
        return type
    }

    /**
     * Construct and return any appropriate help information for
     * the given token.
     *
     * @param token     the pertinent Token object
     * @param tokenText the source String that matched to the token
     * @return ArrayList of PopupHelpItem objects, one per match.
     */
    override fun getTokenExactMatchHelp(token: Token?, tokenText: String?): ArrayList<PopupHelpItem>? {
        var matches: ArrayList<PopupHelpItem>? = null
        if (token != null && token.type == KEYWORD1) {
            val instrMatches =
                Globals.instructionSet.matchOperator(tokenText!!)
            if (instrMatches!!.isNotEmpty()) {
                var realMatches = 0
                matches = arrayListOf()
                for (instrMatch in instrMatches) {
                    if (settings.getBooleanSetting(CoreSettings.EXTENDED_ASSEMBLER_ENABLED) || instrMatch is BasicInstruction) {
                        matches.add(PopupHelpItem(tokenText, instrMatch.exampleFormat, instrMatch.description))
                        realMatches++
                    }
                }
                if (realMatches == 0)
                    matches.add(PopupHelpItem(tokenText, tokenText, "(is not a basic instruction)"))
            }
        }
        if (token != null && token.type == KEYWORD2) {
            val dir = matchDirective(tokenText!!)
            if (dir != null) {
                matches = arrayListOf()
                matches.add(PopupHelpItem(tokenText, dir.getName(), dir.getDescription()))
            }
        }
        return matches
    }


    /**
     * Construct and return any appropriate help information for
     * prefix match based on current line's token list.
     *
     * @param line      String containing current line
     * @param tokenList first Token on current line (head of the linked list)
     * @param tokenAtOffset     the pertinent Token object
     * @param tokenText the source String that matched to the token in the previous parameter
     * @return ArrayList of PopupHelpItem objects, one per match.
     */
    override fun getTokenPrefixMatchHelp(
        line: String,
        tokenList: Token?,
        tokenAtOffset: Token?,
        tokenText: String
    ): ArrayList<PopupHelpItem>? {
        // CASE: Unlikely boundary case...
        if (tokenList == null || tokenList.type == END) return null

        // CASE: if the current token is a comment, turn off the text.
        if (tokenAtOffset != null && tokenAtOffset.type == COMMENT1) return null

        // Let's see if the line already contains an instruction or directive.  If so, we need its token
        // text as well, so we can do the match.  Also need to distinguish the case where the current
        // token is also an instruction/directive (moreThanOneKeyword variable).
        var tokens = tokenList
        var keywordTokenText: String? = null
        var keywordType = NULL
        var offset = 0
        var moreThanOneKeyword = false
        while (tokens!!.type != END) {
            if (tokens.type == KEYWORD1 || tokens.type == KEYWORD2) {
                if (keywordTokenText != null) {
                    moreThanOneKeyword = true
                    break
                }
                keywordTokenText = line.substring(offset, offset + tokens.length)
                keywordType = tokens.type
            }
            offset += tokens.length
            tokens = tokens.next
        }

        // CASE: Current token is a valid MIPS instruction. If this line contains a previous KEYWORD1 or KEYWORD2
        //       token, then we ignore this one and do exact match on the first one. If it does not, there may be longer
        //       instructions for which this is a prefix, so do a prefix match on the current token.
        if (tokenAtOffset != null && tokenAtOffset.type == KEYWORD1)
            return if (moreThanOneKeyword) {
                if (keywordType == KEYWORD1) getTextFromInstructionMatch(keywordTokenText!!, true)
                else getTextFromDirectiveMatch(keywordTokenText!!, true)
            } else getTextFromInstructionMatch(tokenText, false)

        // CASE: Current token is valid KEYWORD2 (MIPS directive). If this line contains a previous KEYWORD1 or KEYWORD2
        //       token, then we ignore this one and do exact match on the first one. If it does not, there may be longer
        //       directives for which this is a prefix, so do a prefix match on the current token.
        if (tokenAtOffset != null && tokenAtOffset.type == KEYWORD2)
            return if (moreThanOneKeyword) {
                if ((keywordType == KEYWORD1)) getTextFromInstructionMatch(keywordTokenText!!, true)
                else getTextFromDirectiveMatch(keywordTokenText!!, true)
            } else getTextFromDirectiveMatch(tokenText, false)

        // CASE: line already contains KEYWORD1 or KEYWORD2 and current token is something other
        //       than KEYWORD1 or KEYWORD2. Generate text based on the exact match of that token.
        if (keywordTokenText != null) {
            if (keywordType == KEYWORD1) return getTextFromInstructionMatch(keywordTokenText, true)
            if (keywordType == KEYWORD2) return getTextFromDirectiveMatch(keywordTokenText, true)
        }


        // CASE: Current token is NULL, which can be any number of things. Think of it as being either whitespace
        //        or an in-progress token possibly preceded by whitespace. We'll do a trim on the token. Now there
        //        are two subcases to consider:
        //    SUBCASE: The line does not contain any KEYWORD1 or KEYWORD2 tokens, but nothing remains after trimming the
        //             current token's text. This means it consists only of whitespace and there is nothing more to do
        //             but return.
        //    SUBCASE: The line does not contain any KEYWORD1 or KEYWORD2 tokens. This means we do a prefix match of
        //             the current token to either instruction or directive names. This is easy to distinguish since
        //             directives start with "."
        if (tokenAtOffset != null && tokenAtOffset.type == NULL) {
            val trimmedTokenText = tokenText.trim { it <= ' ' }

            // Subcase: no KEYWORD1 or KEYWORD2 but current token contains nothing but whitespace.  We're done.
            if (keywordTokenText == null && trimmedTokenText.isEmpty()) return null

            // Subcase: no KEYWORD1 or KEYWORD2.  Generate text based on prefix match of trimmed current token.
            if (keywordTokenText == null && trimmedTokenText.isNotEmpty()) {
                if (trimmedTokenText[0] == '.')
                    return getTextFromDirectiveMatch(trimmedTokenText, false)
                else if (trimmedTokenText.length >= settings.getEditorPopupPrefixLength())
                    return getTextFromInstructionMatch(trimmedTokenText, false)
            }
        }
        // should never get here...
        return null
    }

    private fun getTextFromDirectiveMatch(tokenText: String, exact: Boolean): ArrayList<PopupHelpItem>? {
        var matches: ArrayList<PopupHelpItem>? = null
        var directiveMatches: ArrayList<Directives>? = null
        if (exact) {
            val dir = Directives.matchDirective(tokenText)
            if (dir != null) {
                directiveMatches = arrayListOf()
                directiveMatches.add(dir)
            }
        } else directiveMatches = Directives.prefixMatchDirectives(tokenText)
        if (directiveMatches != null) {
            matches = arrayListOf()
            for (directiveMatch in directiveMatches)
                matches.add(PopupHelpItem(tokenText, directiveMatch.getName(), directiveMatch.getDescription(), exact))
        }
        return matches
    }

    private fun getTextFromInstructionMatch(tokenText: String, exact: Boolean): ArrayList<PopupHelpItem>? {
        val results = arrayListOf<PopupHelpItem>()
        val matches = if (exact) {
            Globals.instructionSet.matchOperator(tokenText)
        } else {
            Globals.instructionSet.prefixMatchOperator(tokenText)
        }
        if (matches == null) return null
        var realMatches = 0
        val instructions = hashMapOf<String, String>()
        val mnemonics = treeSetOf<String>()
        for (match in matches) {
            if (settings.getBooleanSetting(CoreSettings.EXTENDED_ASSEMBLER_ENABLED) || match is BasicInstruction) {
                if (exact) {
                    results.add(PopupHelpItem(tokenText, match.exampleFormat, match.description, exact))
                } else {
                    val mnemonic = match.exampleFormat.split(" ").first()
                    if (!instructions.containsKey(mnemonic)) {
                        mnemonics.add(mnemonic)
                        instructions[mnemonic] = match.description
                    }
                }
                realMatches++
            }
        }
        if (realMatches == 0) {
            if (exact) results.add(PopupHelpItem(tokenText, tokenText, "(not a basic instruction)", exact))
            else return null
        } else {
            if (!exact) {
                for (mnemonic in mnemonics) {
                    val info = instructions[mnemonic]
                    results.add(PopupHelpItem(tokenText, mnemonic, info, exact))
                }
            }
        }
        return results
    }

    private fun doKeyword(line: Segment, i: Int) {
        val i1 = i + 1
        val len = i - lastKeyword
        val type = keywords.lookup(line, lastKeyword, len)
        if (type != Token.Type.NULL) {
            if (lastKeyword != lastOffset)
                addToken(lastKeyword - lastOffset, Token.Type.NULL)
            addToken(len, type)
            lastOffset = i
        }
        lastKeyword = i1
    }

    private fun tokenListContainsKeyword(): Boolean {
        var token = firstToken
        var result = false
        val str = StringBuilder()
        while (token != null) {
            str.append(token.type).append("(").append(token.length).append(") ")
            if (token.type.isKeyword) result = true
            token = token.next
        }
        println("$result $str")
        return result
    }
}