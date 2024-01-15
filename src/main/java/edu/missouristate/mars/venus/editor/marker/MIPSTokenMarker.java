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

package edu.missouristate.mars.venus.editor.marker;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.assembler.Directives;
import edu.missouristate.mars.assembler.TokenTypes;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.Register;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.venus.editor.KeywordMap;
import edu.missouristate.mars.venus.editor.PopupHelpItem;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeSet;

/**
 * MIPS token marker.
 *
 * @author Pete Sanderson (2010) and Slava Pestov (1999)
 */
public class MIPSTokenMarker extends TokenMarker {
    public MIPSTokenMarker() {
        this(getKeywords());
    }

    public MIPSTokenMarker(KeywordMap keywords) {
        this.keywords = keywords;
    }

    public static String[] getMIPSTokenLabels() {
        if (tokenLabels == null) {
            tokenLabels = new String[Token.Type.getEntries().size()];
            tokenLabels[Token.Type.COMMENT1.rawValue] = "Comment";
            tokenLabels[Token.Type.LITERAL1.rawValue] = "String literal";
            tokenLabels[Token.Type.LITERAL2.rawValue] = "Character literal";
            tokenLabels[Token.Type.LABEL.rawValue] = "Label";
            tokenLabels[Token.Type.KEYWORD1.rawValue] = "MIPS instruction";
            tokenLabels[Token.Type.KEYWORD2.rawValue] = "Assembler directive";
            tokenLabels[Token.Type.KEYWORD3.rawValue] = "Register";
            tokenLabels[Token.Type.INVALID.rawValue] = "In-progress, invalid";
            tokenLabels[Token.Type.MACRO_ARG.rawValue] = "Macro parameter";
        }
        return tokenLabels;
    }

    public static String[] getMIPSTokenExamples() {
        if (tokenExamples == null) {
            tokenExamples = new String[Token.Type.getEntries().size()];
            tokenExamples[Token.Type.COMMENT1.rawValue] = "# Load";
            tokenExamples[Token.Type.LITERAL1.rawValue] = "\"First\"";
            tokenExamples[Token.Type.LITERAL2.rawValue] = "'\\n'";
            tokenExamples[Token.Type.LABEL.rawValue] = "main:";
            tokenExamples[Token.Type.KEYWORD1.rawValue] = "lui";
            tokenExamples[Token.Type.KEYWORD2.rawValue] = ".text";
            tokenExamples[Token.Type.KEYWORD3.rawValue] = "$zero";
            tokenExamples[Token.Type.INVALID.rawValue] = "\"Regi";
            tokenExamples[Token.Type.MACRO_ARG.rawValue] = "%arg";
        }
        return tokenExamples;
    }


    public Token.Type markTokensImpl(Token.Type token, Segment line, int lineIndex) {
        char[] array = line.array;
        int offset = line.offset;
        lastOffset = offset;
        lastKeyword = offset;
        int length = line.count + offset;
        boolean backslash = false;

        loop:
        for (int i = offset; i < length; i++) {
            int i1 = (i + 1);

            char c = array[i];
            if (c == '\\') {
                backslash = !backslash;
                continue;
            }

            switch (token) {
                case Token.Type.NULL:
                    switch (c) {
                        case '"':
                            doKeyword(line, i, c);
                            if (backslash)
                                backslash = false;
                            else {
                                addToken(i - lastOffset, token);
                                token = Token.Type.LITERAL1;
                                lastOffset = lastKeyword = i;
                            }
                            break;
                        case '\'':
                            doKeyword(line, i, c);
                            if (backslash)
                                backslash = false;
                            else {
                                addToken(i - lastOffset, token);
                                token = Token.Type.LITERAL2;
                                lastOffset = lastKeyword = i;
                            }
                            break;
                        case ':':
                      /*  Original code for ':' case, replaced 3 Aug 2010. Details below.
                        if(lastKeyword == offset)
                        { // Commenting out this IF statement permits recognition of keywords
                          // used as labels when terminated with ":".  The most common example
                          // is "b:" (where b is mnemonic for branch instruction). DPS 6-July-2010.
                          //
                          // if(doKeyword(line,i,c)) 
                          //   break;
                           backslash = false;
                           addToken(i1 - lastOffset,Token.LABEL);
                           lastOffset = lastKeyword = i1;
                        }
                        else if(doKeyword(line,i,c))
                           break;
                     	break;
                     */
                            // Replacement code 3 Aug 2010.  Will recognize label definitions when:
                            // (1) label is same as instruction name, (2) label begins after column 1,
                            // (3) there are spaces between label name and colon, (4) label is valid
                            // MIPS identifier (otherwise would catch, say, 0 (zero) in .word 0:10)
                            backslash = false;
                            //String lab = new String(array, lastOffset, i1-lastOffset-1).trim();
                            boolean validIdentifier = false;
                            try {
                                validIdentifier = TokenTypes.isValidIdentifier(new String(array, lastOffset, i1 - lastOffset - 1).trim());
                            } catch (StringIndexOutOfBoundsException ignored) {}
                            if (validIdentifier) {
                                addToken(i1 - lastOffset, Token.Type.LABEL);
                                lastOffset = lastKeyword = i1;
                            }
                            break;
                        case '#':
                            backslash = false;
                            doKeyword(line, i, c);
                            if (length - i >= 1) {
                                addToken(i - lastOffset, token);
                                addToken(length - i, Token.Type.COMMENT1);
                                lastOffset = lastKeyword = length;
                                break loop;
                            }
                            break;
                        default:
                            backslash = false;
                            // . and $ added 4/6/10 DPS; % added 12/12 M.Sekhavat
                            if (!Character.isLetterOrDigit(c)
                                    && c != '_' && c != '.' && c != '$' && c != '%')
                                doKeyword(line, i, c);
                            break;
                    }
                    break;
                case Token.Type.LITERAL1:
                    if (backslash)
                        backslash = false;
                    else if (c == '"') {
                        addToken(i1 - lastOffset, token);
                        token = Token.Type.NULL;
                        lastOffset = lastKeyword = i1;
                    }
                    break;
                case Token.Type.LITERAL2:
                    if (backslash)
                        backslash = false;
                    else if (c == '\'') {
                        addToken(i1 - lastOffset, Token.Type.LITERAL1);
                        token = Token.Type.NULL;
                        lastOffset = lastKeyword = i1;
                    }
                    break;
                default:
                    throw new InternalError("Invalid state: "
                            + token);
            }
        }

        if (token == Token.Type.NULL)
            doKeyword(line, length, '\0');

        switch (token) {
            case Token.Type.LITERAL1:
            case Token.Type.LITERAL2:
                addToken(length - lastOffset, Token.Type.INVALID);
                token = Token.Type.NULL;
                break;
            case Token.Type.KEYWORD2:
                addToken(length - lastOffset, token);
                if (!backslash)
                    token = Token.Type.NULL;
            default:
                addToken(length - lastOffset, token);
                break;
        }

        return token;
    }


    /**
     * Construct and return any appropriate help information for
     * the given token.
     *
     * @param token     the pertinent Token object
     * @param tokenText the source String that matched to the token
     * @return ArrayList of PopupHelpItem objects, one per match.
     */
    public ArrayList<PopupHelpItem> getTokenExactMatchHelp(Token token, String tokenText) {
        ArrayList<PopupHelpItem> matches = null;
        if (token != null && token.getType() == Token.Type.KEYWORD1) {
            ArrayList<Instruction> instrMatches = Globals.instructionSet.matchOperator(tokenText);
            if (!instrMatches.isEmpty()) {
                int realMatches = 0;
                matches = new ArrayList<>();
                for (Instruction instrMatch : instrMatches) {
                    if (Globals.getSettings().getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED) || instrMatch instanceof BasicInstruction) {
                        matches.add(new PopupHelpItem(tokenText, instrMatch.getExampleFormat(), instrMatch.getDescription()));
                        realMatches++;
                    }
                }
                if (realMatches == 0) {
                    matches.add(new PopupHelpItem(tokenText, tokenText, "(is not a basic instruction)"));
                }
            }
        }
        if (token != null && token.getType() == Token.Type.KEYWORD2) {
            Directives dir = Directives.matchDirective(tokenText);
            if (dir != null) {
                matches = new ArrayList<>();
                matches.add(new PopupHelpItem(tokenText, dir.getName(), dir.getDescription()));
            }
        }
        return matches;
    }


    /**
     * Construct and return any appropriate help information for
     * prefix match based on current line's token list.
     *
     * @param line      String containing current line
     * @param tokenList first Token on current line (head of linked list)
     * @param token     the pertinent Token object
     * @param tokenText the source String that matched to the token in previous parameter
     * @return ArrayList of PopupHelpItem objects, one per match.
     */

    public ArrayList<PopupHelpItem> getTokenPrefixMatchHelp(String line, Token tokenList, Token token, String tokenText) {
        ArrayList<PopupHelpItem> matches = null;

        // CASE:  Unlikely boundary case...
        if (tokenList == null || tokenList.getType() == Token.Type.END) {
            return null;
        }

        // CASE:  if current token is a comment, turn off the text.
        if (token != null && token.getType() == Token.Type.COMMENT1) {
            return null;
        }

        // Let's see if the line already contains an instruction or directive.  If so, we need its token
        // text as well so we can do the match.  Also need to distinguish the case where current
        // token is also an instruction/directive (moreThanOneKeyword variable).

        Token tokens = tokenList;
        String keywordTokenText = null;
        Token.Type keywordType = Token.Type.NULL;
        int offset = 0;
        boolean moreThanOneKeyword = false;
        while (tokens.getType() != Token.Type.END) {
            if (tokens.getType() == Token.Type.KEYWORD1 || tokens.getType() == Token.Type.KEYWORD2) {
                if (keywordTokenText != null) {
                    moreThanOneKeyword = true;
                    break;
                }
                keywordTokenText = line.substring(offset, offset + tokens.getLength());
                keywordType = tokens.getType();
            }
            offset += tokens.getLength();
            tokens = tokens.getNext();
        }

        // CASE:  Current token is valid KEYWORD1 (MIPS instruction).  If this line contains a previous KEYWORD1 or KEYWORD2
        //        token, then we ignore this one and do exact match on the first one.  If it does not, there may be longer
        //        instructions for which this is a prefix, so do a prefix match on current token.
        if (token != null && token.getType() == Token.Type.KEYWORD1) {
            if (moreThanOneKeyword) {
                return (keywordType == Token.Type.KEYWORD1) ? getTextFromInstructionMatch(keywordTokenText, true)
                        : getTextFromDirectiveMatch(keywordTokenText, true);
            } else {
                return getTextFromInstructionMatch(tokenText, false);
            }
        }

        // CASE:  Current token is valid KEYWORD2 (MIPS directive).  If this line contains a previous KEYWORD1 or KEYWORD2
        //        token, then we ignore this one and do exact match on the first one.  If it does not, there may be longer
        //        directives for which this is a prefix, so do a prefix match on current token.
        if (token != null && token.getType() == Token.Type.KEYWORD2) {
            if (moreThanOneKeyword) {
                return (keywordType == Token.Type.KEYWORD1) ? getTextFromInstructionMatch(keywordTokenText, true)
                        : getTextFromDirectiveMatch(keywordTokenText, true);
            } else {
                return getTextFromDirectiveMatch(tokenText, false);
            }
        }

        // CASE: line already contains KEYWORD1 or KEYWORD2 and current token is something other
        //       than KEYWORD1 or KEYWORD2. Generate text based on exact match of that token.
        if (keywordTokenText != null) {
            if (keywordType == Token.Type.KEYWORD1) {
                return getTextFromInstructionMatch(keywordTokenText, true);
            }
            if (keywordType == Token.Type.KEYWORD2) {
                return getTextFromDirectiveMatch(keywordTokenText, true);
            }
        }

        // CASE:  Current token is NULL, which can be any number of things.  Think of it as being either white space
        //        or an in-progress token possibly preceded by white space.  We'll do a trim on the token.  Now there
        //        are two subcases to consider:
        //    SUBCASE: The line does not contain any KEYWORD1 or KEYWORD2 tokens but nothing remains after trimming the
        //             current token's text.  This means it consists only of white space and there is nothing more to do
        //             but return.
        //    SUBCASE: The line does not contain any KEYWORD1 or KEYWORD2 tokens.  This means we do a prefix match of
        //             of the current token to either instruction or directive names.  Easy to distinguish since
        //             directives start with "."


        if (token != null && token.getType() == Token.Type.NULL) {

            String trimmedTokenText = tokenText.trim();

            // Subcase: no KEYWORD1 or KEYWORD2 but current token contains nothing but white space.  We're done.
            if (keywordTokenText == null && trimmedTokenText.isEmpty()) {
                return null;
            }

            // Subcase: no KEYWORD1 or KEYWORD2.  Generate text based on prefix match of trimmed current token.
            if (keywordTokenText == null && !trimmedTokenText.isEmpty()) {
                if (trimmedTokenText.charAt(0) == '.') {
                    return getTextFromDirectiveMatch(trimmedTokenText, false);
                } else if (trimmedTokenText.length() >= Globals.getSettings().getEditorPopupPrefixLength()) {
                    return getTextFromInstructionMatch(trimmedTokenText, false);
                }
            }
        }
        // should never get here...
        return null;
    }


    ///////////////////////////////////////////////////////////////////////////
    // Return ArrayList of PopupHelpItem for match of directives.  If second argument
    // true, will do exact match.  If false, will do prefix match.  Returns null
    // if no matches.
    private ArrayList<PopupHelpItem> getTextFromDirectiveMatch(String tokenText, boolean exact) {
        ArrayList<PopupHelpItem> matches = null;
        ArrayList<Directives> directiveMatches = null;
        if (exact) {
            Directives dir = Directives.matchDirective(tokenText);
            if (dir != null) {
                directiveMatches = new ArrayList<>();
                directiveMatches.add(dir);
            }
        } else {
            directiveMatches = Directives.prefixMatchDirectives(tokenText);
        }
        if (directiveMatches != null) {
            matches = new ArrayList<>();
            for (Directives directiveMatch : directiveMatches) {
                matches.add(new PopupHelpItem(tokenText, directiveMatch.getName(), directiveMatch.getDescription(), exact));
            }
        }
        return matches;
    }

    // Return text for match of instruction mnemonic.  If second argument true, will
    // do exact match.  If false, will do prefix match.   Text is returned as ArrayList
    // of PopupHelpItem objects. If no matches, returns null.
    private ArrayList<PopupHelpItem> getTextFromInstructionMatch(String tokenText, boolean exact) {
        String text = null;
        ArrayList<Instruction> matches;
        ArrayList<PopupHelpItem> results = new ArrayList<>();
        if (exact) {
            matches = Globals.instructionSet.matchOperator(tokenText);
        } else {
            matches = Globals.instructionSet.prefixMatchOperator(tokenText);
        }
        if (matches == null) {
            return null;
        }
        int realMatches = 0;
        HashMap<String, String> insts = new HashMap<>();
        TreeSet<String> mnemonics = new TreeSet<>();
        for (Instruction match : matches) {
            if (Globals.getSettings().getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED) || match instanceof BasicInstruction) {
                if (exact) {
                    results.add(new PopupHelpItem(tokenText, match.getExampleFormat(), match.getDescription(), exact));
                } else {
                    String mnemonic = match.getExampleFormat().split(" ")[0];
                    if (!insts.containsKey(mnemonic)) {
                        mnemonics.add(mnemonic);
                        insts.put(mnemonic, match.getDescription());
                    }
                }
                realMatches++;
            }
        }
        if (realMatches == 0) {
            if (exact) {
                results.add(new PopupHelpItem(tokenText, tokenText, "(not a basic instruction)", exact));
            } else {
                return null;
            }
        } else {
            if (!exact) {
                for (String o : mnemonics) {
                    String info = insts.get(o);
                    results.add(new PopupHelpItem(tokenText, o, info, exact));
                }
            }
        }
        return results;
    }


    /**
     * Get KeywordMap containing all MIPS key words.  This includes all instruction mnemonics,
     * assembler directives, and register names.
     *
     * @return KeywordMap where key is the keyword and associated value is the token type (e.g. Token.KEYWORD1).
     */


    public static KeywordMap getKeywords() {
        if (cKeywords == null) {
            cKeywords = new KeywordMap(false);
            // add Instruction mnemonics
            ArrayList<Instruction> instructionSet = Globals.instructionSet.getInstructionList();
            for (Instruction instruction : instructionSet) {
                cKeywords.add(instruction.getName(), Token.Type.KEYWORD1);
            }
            // add assembler directives
            ArrayList<Directives> directiveSet = Directives.getDirectiveList();
            for (Directives directive : directiveSet) {
                cKeywords.add(directive.getName(), Token.Type.KEYWORD2);
            }
            // add integer register file
            Register[] registerFile = RegisterFile.getRegisters();
            for (int i = 0; i < registerFile.length; i++) {
                cKeywords.add(registerFile[i].getName(), Token.Type.KEYWORD3);
                cKeywords.add("$" + i, Token.Type.KEYWORD3);  // also recognize $0, $1, $2, etc
            }
            // add Coprocessor 1 (floating point) register file
            Register[] coprocessor1RegisterFile = Coprocessor1.getRegisters();
            for (Register register : coprocessor1RegisterFile) {
                cKeywords.add(register.getName(), Token.Type.KEYWORD3);
            }
            // Note: Coprocessor 0 registers referenced only by number: $8, $12, $13, $14. These are already in the map

        }
        return cKeywords;
    }

    // private members
    private static KeywordMap cKeywords;
    private static String[] tokenLabels, tokenExamples;
    private final KeywordMap keywords;
    private int lastOffset;
    private int lastKeyword;

    private void doKeyword(Segment line, int i, char c) {
        int i1 = i + 1;

        int len = i - lastKeyword;
        Token.Type id = keywords.lookup(line, lastKeyword, len);
        if (id != Token.Type.NULL) {
            // If this is a Token.KEYWORD1 and line already contains a keyword,
            // then assume this one is a label reference and ignore it.
            //   if (id == Token.KEYWORD1 && tokenListContainsKeyword()) {
            //    }
            //    else {
            if (lastKeyword != lastOffset)
                addToken(lastKeyword - lastOffset, Token.Type.NULL);
            addToken(len, id);
            lastOffset = i;
            //  }
        }
        lastKeyword = i1;
    }

    private boolean tokenListContainsKeyword() {
        Token token = getFirstToken();
        boolean result = false;
        StringBuilder str = new StringBuilder();
        while (token != null) {
            str.append(token.getType()).append("(").append(token.getLength()).append(") ");
            if (token.getType() == Token.Type.KEYWORD1 || token.getType() == Token.Type.KEYWORD2 || token.getType() == Token.Type.KEYWORD3)
                result = true;
            token = token.getNext();
        }
        System.out.println(result + " " + str);
        return result;
    }
}
