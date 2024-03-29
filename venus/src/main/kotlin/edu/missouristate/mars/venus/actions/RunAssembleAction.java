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

package edu.missouristate.mars.venus.actions;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.venus.FileStatus;
import edu.missouristate.mars.venus.VenusUI;
import edu.missouristate.mars.venus.panes.ExecutePane;
import edu.missouristate.mars.venus.panes.RegistersPane;

import java.util.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Run -> Assemble menu item (and toolbar icon)
 */
public class RunAssembleAction extends GuiAction {

    private static ArrayList<MIPSProgram> MIPSProgramsToAssemble;
    private static boolean extendedAssemblerEnabled;
    private static boolean warningsAreErrors;
    // Threshold for adding filename to printed message of files being assembled.
    private static final int LINE_LENGTH_LIMIT = 60;

    public RunAssembleAction(String name, Icon icon, String descrip,
                             Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    // These are both used by RunResetAction to re-assemble under identical conditions.
    public static ArrayList<MIPSProgram> getMIPSProgramsToAssemble() {
        return MIPSProgramsToAssemble;
    }

    static boolean getExtendedAssemblerEnabled() {
        return extendedAssemblerEnabled;
    }

    static boolean getWarningsAreErrors() {
        return warningsAreErrors;
    }

    public void actionPerformed(ActionEvent e) {
        String name = this.getValue(Action.NAME).toString();
        Component editPane = mainUI.getMainPane().getEditPane();
        ExecutePane executePane = mainUI.getMainPane().getExecutePane();
        RegistersPane registersPane = mainUI.getRegistersPane();
        extendedAssemblerEnabled = Globals.getSettings().getBooleanSetting(CoreSettings.EXTENDED_ASSEMBLER_ENABLED);
        warningsAreErrors = Globals.getSettings().getBooleanSetting(CoreSettings.WARNINGS_ARE_ERRORS);
        if (FileStatus.getFile() != null) {
            if (FileStatus.Companion.getStatus() == FileStatus.StatusType.EDITED) {
                mainUI.getEditor().save();
            }
            try {
                Globals.program = new MIPSProgram();
                ArrayList<String> filesToAssemble;
                if (Globals.getSettings().getBooleanSetting(CoreSettings.ASSEMBLE_ALL_ENABLED)) {// setting calls for multiple file assembly
                    filesToAssemble = FilenameFinder.getFilenameList(
                            new File(FileStatus.getName()).getParent(), Globals.getFileExtensions());
                } else {
                    filesToAssemble = new ArrayList<>();
                    filesToAssemble.add(FileStatus.getName());
                }
                String exceptionHandler = null;
                if (Globals.getSettings().getBooleanSetting(CoreSettings.EXCEPTION_HANDLER_ENABLED) &&
                        Globals.getSettings().getExceptionHandler() != null &&
                        !Globals.getSettings().getExceptionHandler().isEmpty()) {
                    exceptionHandler = Globals.getSettings().getExceptionHandler();
                }
                MIPSProgramsToAssemble = Globals.program.prepareFilesForAssembly(filesToAssemble, FileStatus.getFile().getPath(), exceptionHandler);
                mainUI.getMessagesPane().postMarsMessage(buildFileNameList(name + ": assembling ", MIPSProgramsToAssemble));
                // added logic to receive any warnings and output them.... DPS 11/28/06
                ErrorList warnings = Globals.program.assemble(MIPSProgramsToAssemble, extendedAssemblerEnabled,
                        warningsAreErrors);
                if (warnings.hasWarnings()) {
                    mainUI.getMessagesPane().postMarsMessage(warnings.generateReport(true));
                }
                mainUI.getMessagesPane().postMarsMessage(
                        name + ": operation completed successfully.\n\n");
                FileStatus.setAssembled(true);
                FileStatus.Companion.setStatus(FileStatus.StatusType.RUNNABLE);
                RegisterFile.resetRegisters();
                Coprocessor1.resetRegisters();
                Coprocessor0.resetRegisters();
                executePane.getTextSegmentWindow().setupTable();
                executePane.getDataSegmentWindow().setupTable();
                executePane.getDataSegmentWindow().highlightCellForAddress(Memory.getDataBaseAddress());
                executePane.getDataSegmentWindow().clearHighlighting();
                executePane.getLabelsWindow().setupTable();
                executePane.getTextSegmentWindow().setCodeHighlighting(true);
                executePane.getTextSegmentWindow().highlightStepAtPC();
                registersPane.getRegistersWindow().clearWindow();
                registersPane.getCoprocessor1Window().clearWindow();
                registersPane.getCoprocessor0Window().clearWindow();
                VenusUI.setReset(true);
                VenusUI.setStarted(false);
                mainUI.getMainPane().setSelectedComponent(executePane);

                // Aug. 24, 2005 Ken Vollmar
                SystemIO.resetFiles();  // Ensure that I/O "file descriptors" are initialized for a new program run

            } catch (ProcessingException pe) {
                String errorReport = pe.errors().generateErrorAndWarningReport();
                mainUI.getMessagesPane().postMarsMessage(errorReport);
                mainUI.getMessagesPane().postMarsMessage(
                        name + ": operation completed with errors.\n\n");
                // Select editor line containing first error, and corresponding error message.
                ArrayList<ErrorMessage> errorMessages = pe.errors().getMessages();
                for (ErrorMessage errorMessage : errorMessages) {
                    ErrorMessage em = errorMessage;
                    // No line or position may mean File Not Found (e.g. exception file). Don't try to open. DPS 3-Oct-2010
                    if (em.getLine() == 0 && em.getPosition() == 0) {
                        continue;
                    }
                    if (!em.isWarning() || warningsAreErrors) {
                        Globals.getGui().getMessagesPane().selectErrorMessage(em.getFilename(), em.getLine(), em.getPosition());
                        // Bug workaround: Line selection does not work correctly for the JEditTextArea editor
                        // when the file is opened then automatically assembled (assemble-on-open setting).
                        // Automatic assemble happens in EditTabbedPane's openFile() method, by invoking
                        // this method (actionPerformed) explicitly with null argument.  Thus e!=null test.
                        // DPS 9-Aug-2010
                        if (e != null) {
                            Globals.getGui().getMessagesPane().selectEditorTextLine(em.getFilename(), em.getLine(), em.getPosition());
                        }
                        break;
                    }
                }
                FileStatus.setAssembled(false);
                FileStatus.Companion.setStatus(FileStatus.StatusType.NOT_EDITED);
            }
        }
    }

    // Handy little utility for building comma-separated list of filenames
    // while not letting line length get out of hand.
    private String buildFileNameList(String preamble, ArrayList<MIPSProgram> programList) {
        StringBuilder result = new StringBuilder(preamble);
        int lineLength = result.length();
        for (int i = 0; i < programList.size(); i++) {
            String filename = programList.get(i).getFilename();
            result.append(filename).append((i < programList.size() - 1) ? ", " : "");
            lineLength += filename.length();
            if (lineLength > LINE_LENGTH_LIMIT) {
                result.append("\n");
                lineLength = 0;
            }
        }
        return result + ((lineLength == 0) ? "" : "\n") + "\n";
    }
}
