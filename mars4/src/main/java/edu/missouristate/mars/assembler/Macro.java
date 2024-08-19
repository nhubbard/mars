/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.assembler;

import java.util.ArrayList;
import java.util.Collections;

import edu.missouristate.mars.ErrorList;
import edu.missouristate.mars.ErrorMessage;
import edu.missouristate.mars.MIPSProgram;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Stores information of a macro definition.
 *
 * @author M.H.Sekhavat <sekhavat17@gmail.com>
 */
public class Macro {
    private String name;
    @Nullable
    private MIPSProgram program;
    private final @NotNull ArrayList<String> labels;

    /**
     * first and last line number of macro definition. first line starts with
     * .macro directive and last line is .end_macro directive.
     */
    private int fromLine, toLine;
    private int origFromLine, origToLine;
    /**
     * arguments like <code>%arg</code> will be substituted by macro expansion
     */
    private ArrayList<String> args;

    public Macro() {
        name = "";
        program = null;
        fromLine = toLine = 0;
        origFromLine = origToLine = 0;
        args = new ArrayList<>();
        labels = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public @Nullable MIPSProgram getProgram() {
        return program;
    }

    public void setProgram(@Nullable MIPSProgram program) {
        this.program = program;
    }

    public int getFromLine() {
        return fromLine;
    }

    public int getOriginalFromLine() {
        return this.origFromLine;
    }

    public void setFromLine(int fromLine) {
        this.fromLine = fromLine;
    }

    public void setOriginalFromLine(int origFromLine) {
        this.origFromLine = origFromLine;
    }

    public int getToLine() {
        return toLine;
    }

    public int getOriginalToLine() {
        return this.origToLine;
    }

    public void setToLine(int toLine) {
        this.toLine = toLine;
    }

    public void setOriginalToLine(int origToLine) {
        this.origToLine = origToLine;
    }

    public ArrayList<String> getArgs() {
        return args;
    }

    public void setArgs(ArrayList<String> args) {
        this.args = args;
    }

    /**
     * @param obj {@link Macro} object to check if their name and count of
     *            arguments are same
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Macro macro) return macro.getName().equals(name) && (macro.args.size() == args.size());
        return super.equals(obj);
    }

    public void addArg(String value) {
        args.add(value);
    }

    /**
     * Substitutes macro arguments in a line of source code inside macro
     * definition to be parsed after macro expansion. <br>
     * Also appends "_M#" to all labels defined inside macro body where # is value of <code>counter</code>
     *
     * @param line    source line number in macro definition to be substituted
     * @param counter unique macro expansion id
     * @return <code>line</code>-th line of source code, with substituted
     * arguments
     */
    public @Nullable String getSubstitutedLine(int line, @NotNull TokenList args, long counter, @NotNull ErrorList errors) {
        TokenList tokens = program.getTokenList().get(line - 1);
        String s = program.getSourceLine(line);

        for (int i = tokens.size() - 1; i >= 0; i--) {
            Token token = tokens.get(i);
            if (tokenIsMacroParameter(token.getValue(), true)) {
                int repl = -1;
                for (int j = 0; j < this.args.size(); j++) {
                    if (this.args.get(j).equals(token.getValue())) {
                        repl = j;
                        break;
                    }
                }
                String substitute = token.getValue();
                if (repl != -1) substitute = args.get(repl + 1).toString();
                else errors.add(new ErrorMessage(
                    program,
                    token.getSourceLine(),
                    token.getStartPos(),
                    "Unknown macro parameter"
                ));
                s = replaceToken(s, token, substitute);
            } else if (tokenIsMacroLabel(token.getValue())) {
                String substitute = token.getValue() + "_M" + counter;
                s = replaceToken(s, token, substitute);
            }
        }
        return s;
    }

    /**
     * @return true if <code>value</code> is name of a label defined in this macro's body.
     */
    boolean tokenIsMacroLabel(String value) {
        return (Collections.binarySearch(labels, value) >= 0);
    }

    /**
     * replaces token <code>tokenToBeReplaced</code> which is occured in <code>source</code> with <code>substitute</code>.
     */
    @NotNull String replaceToken(@NotNull String source, @NotNull Token tokenToBeReplaced, String substitute) {
        /*
         Initially, the position of the substitute was based on token position but that proved problematic
         in that the source string does not always match the token list from which the token comes. The
         token list has already had .eqv equivalences applied whereas the source may not.  This is because
         the source comes from a macro definition?  That has proven to be a tough question to answer.
         DPS 12-feb-2013
        */
        String stringToBeReplaced = tokenToBeReplaced.getValue();
        int pos = source.indexOf(stringToBeReplaced);
        return (pos < 0)
                ? source
                : source.substring(0, pos) + substitute + source.substring(pos + stringToBeReplaced.length());
    }

    /**
     * returns whether <code>tokenValue</code> is macro parameter or not
     *
     * @param acceptSpimStyleParameters accepts SPIM-style parameters which begin with '$' if true
     */
    public static boolean tokenIsMacroParameter(@NotNull String tokenValue, boolean acceptSpimStyleParameters) {
        if (acceptSpimStyleParameters) {
            /*
             Bug fix: SPIM accepts parameter names that start with $ instead of %.  This can
             lead to problems since register names also start with $.  This IF condition
             should filter out register names.  Originally filtered those from regular set but not
             from Coprocessor0 or Coprocessor1 register sets.  Expanded the condition.
             DPS 7-July-2014.
            */
            if (!tokenValue.isEmpty() &&
                    tokenValue.charAt(0) == '$' &&
                    RegisterFile.getUserRegister(tokenValue) == null &&
                    Coprocessor0.getRegister(tokenValue) == null &&  // added 7-July-2014
                    Coprocessor1.getRegister(tokenValue) == null) {  // added 7-July-2014
                return true;
            }
        }
        return tokenValue.length() > 1 && tokenValue.charAt(0) == '%';
    }

    public void addLabel(String value) {
        labels.add(value);
    }

    /**
     * Operations to be done on this macro before it is committed in macro pool.
     */
    public void readyForCommit() {
        Collections.sort(labels);
    }
}
