package edu.missouristate.mars.assembler;

import edu.missouristate.mars.MIPSProgram;
import org.jetbrains.annotations.Nullable;

/**
 * Handy class to represent, for a given line of source code, the code
 * itself, the program containing it, and its line number within that program.
 * This is used to separately keep track of the original file/position of
 * a given line of code.  When .include is used, it will migrate to a different
 * line and possibly different program but the migration should not be visible
 * to the user.
 */
public class SourceLine {
    private final String source;
    @Nullable private String filename;
    private final @Nullable MIPSProgram mipsProgram;
    private final int lineNumber;

    /**
     * SourceLine constructor
     *
     * @param source      The source code itself
     * @param mipsProgram The program (object representing source file) containing that line
     * @param lineNumber  The line number within that program where source appears.
     */
    public SourceLine(String source, @Nullable MIPSProgram mipsProgram, int lineNumber) {
        this.source = source;
        this.mipsProgram = mipsProgram;
        if (mipsProgram != null)
            this.filename = mipsProgram.getFilename();
        this.lineNumber = lineNumber;
    }

    /**
     * Retrieve source statement itself
     *
     * @return Source statement as String
     */
    public String getSource() {
        return source;
    }

    /**
     * Retrieve name of file containing source statement
     *
     * @return File name as String
     */
    @Nullable
    public String getFilename() {
        return filename;
    }

    /**
     * Retrieve line number of source statement
     *
     * @return Line number of source statement
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * Retrieve MIPSProgram object containing source statement
     *
     * @return program as MIPSProgram object
     */
    public MIPSProgram getMIPSProgram() {
        return mipsProgram;
    }
}