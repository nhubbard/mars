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

package edu.missouristate.mars;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;

/**
 * Maintains a list of generated error messages, regardless of source (tokenizing, parsing,
 * assembly, execution).
 *
 * @author Pete Sanderson
 * @version August 2003
 **/

public class ErrorList {
    private final @NotNull ArrayList<ErrorMessage> messages;
    private int errorCount;
    private int warningCount;
    public static final String ERROR_MESSAGE_PREFIX = "Error";
    public static final String WARNING_MESSAGE_PREFIX = "Warning";
    public static final String FILENAME_PREFIX = " in ";
    public static final String LINE_PREFIX = " line ";
    public static final String POSITION_PREFIX = " column ";
    public static final String MESSAGE_SEPARATOR = ": ";


    /**
     * Constructor for ErrorList
     **/

    public ErrorList() {
        messages = new ArrayList<>();
        errorCount = 0;
        warningCount = 0;
    }

    /**
     * Get ArrayList of error messages.
     *
     * @return ArrayList of ErrorMessage objects
     */
    public @NotNull ArrayList<ErrorMessage> getErrorMessages() {
        return messages;
    }

    /**
     * Determine whether error has occured or not.
     *
     * @return <tt>true</tt> if an error has occurred (does not include warnings), <tt>false</tt> otherwise.
     **/
    public boolean errorsOccurred() {
        return (errorCount != 0);
    }

    /**
     * Determine whether warning has occured or not.
     *
     * @return <tt>true</tt> if an warning has occurred, <tt>false</tt> otherwise.
     **/
    public boolean warningsOccurred() {
        return (warningCount != 0);
    }

    /**
     * Add new error message to end of list.
     *
     * @param mess ErrorMessage object to be added to end of error list.
     **/
    public void add(@NotNull ErrorMessage mess) {
        add(mess, messages.size());
    }

    /**
     * Add new error message at specified index position.
     *
     * @param mess  ErrorMessage object to be added to end of error list.
     * @param index position in error list
     **/
    public void add(@NotNull ErrorMessage mess, int index) {
        if (errorCount > getErrorLimit()) {
            return;
        }
        if (errorCount == getErrorLimit()) {
            messages.add(new ErrorMessage((MIPSProgram) null, mess.getLine(), mess.getPosition(), "Error Limit of " + getErrorLimit() + " exceeded."));
            errorCount++; // subsequent errors will not be added; see if statement above
            return;
        }
        messages.add(index, mess);
        if (mess.isWarning()) {
            warningCount++;
        } else {
            errorCount++;
        }
    }


    /**
     * Count of number of error messages in list.
     *
     * @return Number of error messages in list.
     **/

    public int errorCount() {
        return this.errorCount;
    }

    /**
     * Count of number of warning messages in list.
     *
     * @return Number of warning messages in list.
     **/

    public int warningCount() {
        return this.warningCount;
    }

    /**
     * Check to see if error limit has been exceeded.
     *
     * @return True if error limit exceeded, false otherwise.
     **/

    public boolean errorLimitExceeded() {
        return this.errorCount > getErrorLimit();
    }

    /**
     * Get limit on number of error messages to be generated
     * by one assemble operation.
     *
     * @return error limit.
     **/

    public int getErrorLimit() {
        return Globals.maximumErrorMessages;
    }

    /**
     * Produce error report.
     *
     * @return String containing report.
     **/
    public @NotNull String generateErrorReport() {
        return generateReport(ErrorMessage.ERROR);
    }

    /**
     * Produce warning report.
     *
     * @return String containing report.
     **/
    public @NotNull String generateWarningReport() {
        return generateReport(ErrorMessage.WARNING);
    }

    /**
     * Produce report containing both warnings and errors, warnings first.
     *
     * @return String containing report.
     **/
    public @NotNull String generateErrorAndWarningReport() {
        return generateWarningReport() + generateErrorReport();
    }

    // Produces either error or warning report.
    private @NotNull String generateReport(boolean isWarning) {
        StringBuilder report = new StringBuilder();
        String reportLine;
        for (ErrorMessage message : messages) {
            if ((isWarning && message.isWarning()) || (!isWarning && !message.isWarning())) {
                reportLine = ((isWarning) ? WARNING_MESSAGE_PREFIX : ERROR_MESSAGE_PREFIX) + FILENAME_PREFIX;
                if (!message.getFilename().isEmpty())
                    reportLine = reportLine + (new File(message.getFilename()).getPath()); //.getName());
                if (message.getLine() > 0)
                    reportLine = reportLine + LINE_PREFIX + message.getMacroExpansionHistory() + message.getLine();
                if (message.getPosition() > 0)
                    reportLine = reportLine + POSITION_PREFIX + message.getPosition();
                reportLine = reportLine + MESSAGE_SEPARATOR + message.getMessage() + "\n";
                report.append(reportLine);
            }
        }
        return report.toString();
    }
}  // ErrorList

