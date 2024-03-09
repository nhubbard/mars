package edu.missouristate.mars.assembler;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.MIPSProgram;
import edu.missouristate.mars.util.ExcludeFromJacocoGeneratedReport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/**
 * Stores information of macros defined by now. <br>
 * Will be used in first pass of assembling MIPS source code. When reached
 * <code>.macro</code> directive, parser calls
 * {@link MacroPool#beginMacro} and skips source code lines until
 * reaches <code>.end_macro</code> directive. then calls
 * {@link MacroPool#commitMacro} and the macro information stored in a
 * {@link Macro} instance will be added to {@link #macroList}. <br>
 * Each {@link MIPSProgram} will have one {@link MacroPool}<br>
 * NOTE: Forward referencing macros (macro expansion before its definition in
 * source code) and Nested macro definition (defining a macro inside other macro
 * definition) are not supported.
 *
 * @author M.H.Sekhavat <sekhavat17@gmail.com>
 */
public class MacroPool {
    private final MIPSProgram program;

    /**
     * List of macros defined by now
     */
    private final @NotNull ArrayList<Macro> macroList;

    /**
     * @see MacroPool#beginMacro
     */
    @Nullable private Macro current;

    private final @NotNull ArrayList<Integer> callStack;

    private final @NotNull ArrayList<Integer> callStackOrigLines;

    /**
     * @see #getNextCounter()
     */
    private int counter;

    /**
     * Create an empty MacroPool for given program
     *
     * @param mipsProgram associated MIPS program
     */
    public MacroPool(MIPSProgram mipsProgram) {
        this.program = mipsProgram;
        macroList = new ArrayList<>();
        callStack = new ArrayList<>();
        callStackOrigLines = new ArrayList<>();
        current = null;
        counter = 0;
    }

    /**
     * This method will be called by parser when reached <code>.macro</code>
     * directive.<br>
     * Instantiates a new {@link Macro} object and stores it in {@link #current}
     * . {@link #current} will be added to {@link #macroList} by
     * {@link #commitMacro}
     *
     * @param nameToken Token containing name of macro after <code>.macro</code> directive
     */
    public void beginMacro(@NotNull Token nameToken) {
        current = new Macro();
        current.setName(nameToken.getValue());
        current.setFromLine(nameToken.getSourceLine());
        current.setOriginalFromLine(nameToken.getOriginalSourceLine());
        current.setProgram(program);
    }

    /**
     * This method will be called by parser when reached <code>.end_macro</code>
     * directive. <br>
     * Adds/Replaces {@link #current} macro into the {@link #macroList}.
     *
     * @param endToken Token containing <code>.end_macro</code> directive in source code
     */
    public void commitMacro(@NotNull Token endToken) {
        current.setToLine(endToken.getSourceLine());
        current.setOriginalToLine(endToken.getOriginalSourceLine());
        current.readyForCommit();
        macroList.add(current);
        current = null;
    }

    /**
     * Will be called by parser when reaches a macro expansion call
     *
     * @param tokens tokens passed to macro expansion call
     * @return {@link Macro} object matching the name and argument count of
     * tokens passed
     */
    @Nullable
    public Macro getMatchingMacro(@NotNull TokenList tokens, int callerLine) {
        if (tokens.isEmpty()) return null;
        @Nullable Macro ret = null;
        Token firstToken = tokens.get(0);
        for (Macro macro : macroList) {
            if (macro.getName().equals(firstToken.getValue()) && macro.getArgs().size() + 1 == tokens.size() && (ret == null || ret.getFromLine() < macro.getFromLine()))
                ret = macro;
        }
        return ret;
    }

    /**
     * @return true if any macros have been defined with name <code>value</code>
     * by now, not concerning arguments count.
     */
    public boolean matchesAnyMacroName(String value) {
        for (Macro macro : macroList)
            if (macro.getName().equals(value)) return true;
        return false;
    }

    @Nullable
    public Macro getCurrent() {
        return current;
    }

    @ExcludeFromJacocoGeneratedReport
    public void setCurrent(@Nullable Macro current) {
        this.current = current;
    }

    /**
     * {@link #counter} will be set to 0 on construction of this class and will
     * be incremented by each call. parser calls this method once for every
     * expansions. it will be a unique id for each expansion of macro in a file
     *
     * @return counter value
     */
    public int getNextCounter() {
        return counter++;
    }

    @ExcludeFromJacocoGeneratedReport
    public @NotNull ArrayList<Integer> getCallStack() {
        return callStack;
    }

    public boolean pushOnCallStack(@NotNull Token token) { //returns true if detected expansion loop
        int sourceLine = token.getSourceLine();
        int origSourceLine = token.getOriginalSourceLine();
        if (callStack.contains(sourceLine))
            return true;
        callStack.add(sourceLine);
        callStackOrigLines.add(origSourceLine);
        return false;
    }

    public void popFromCallStack() {
        callStack.remove(callStack.size() - 1);
        callStackOrigLines.remove(callStackOrigLines.size() - 1);
    }

    public @NotNull String getExpansionHistory() {
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < callStackOrigLines.size(); i++) {
            if (i > 0) ret.append("->");
            ret.append(callStackOrigLines.get(i).toString());
        }
        return ret.toString();
    }

    @ExcludeFromJacocoGeneratedReport
    @NotNull ArrayList<Macro> getMacrosUnderTesting() {
        if (!Globals.isRunningTest())
            throw new IllegalStateException("This method is only for use in tests. DO NOT USE OUTSIDE OF TESTS.");
        return macroList;
    }
}
