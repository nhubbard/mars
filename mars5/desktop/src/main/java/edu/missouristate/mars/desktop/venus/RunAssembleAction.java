package edu.missouristate.mars.desktop.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.ApplicationInfo;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.util.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import javax.swing.*;

/*
Copyright (c) 2003-2010,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Action class for the Run -&gt; Assemble menu item (and toolbar icon) */
public class RunAssembleAction extends GuiAction {

  private static ArrayList<MIPSprogram> MIPSprogramsToAssemble;
  private static boolean extendedAssemblerEnabled;
  private static boolean warningsAreErrors;
  // Threshold for adding filename to printed message of files being assembled.
  private static final int LINE_LENGTH_LIMIT = 60;

  public RunAssembleAction(
      String name, Icon icon, String descrip, Integer mnemonic, KeyStroke accel, VenusUI gui) {
    super(name, icon, descrip, mnemonic, accel, gui);
  }

  // These are both used by RunResetAction to re-assemble under identical conditions.
  static ArrayList<MIPSprogram> getMIPSprogramsToAssemble() {
    return MIPSprogramsToAssemble;
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
    extendedAssemblerEnabled =
        mainUI
            .session()
            .getSettings()
            .getBooleanSetting(edu.missouristate.mars.Settings.EXTENDED_ASSEMBLER_ENABLED);
    warningsAreErrors =
        mainUI
            .session()
            .getSettings()
            .getBooleanSetting(edu.missouristate.mars.Settings.WARNINGS_ARE_ERRORS);
    if (mainUI.editorState().getFile() != null) {
      if (mainUI.editorState().get() == FileStatus.EDITED) {
        mainUI.editor.save();
      }
      try {
        mainUI.session().setProgram(mainUI.session().createProgram());
        ArrayList<String> filesToAssemble;
        if (mainUI
            .session()
            .getSettings()
            .getBooleanSetting(
                edu.missouristate.mars.Settings
                    .ASSEMBLE_ALL_ENABLED)) { // setting calls for multiple file assembly
          filesToAssemble =
              FilenameFinder.getFilenameList(
                  new File(mainUI.editorState().getName()).getParent(),
                  ApplicationInfo.fileExtensions);
        } else {
          filesToAssemble = new ArrayList<>();
          filesToAssemble.add(mainUI.editorState().getName());
        }
        String exceptionHandler = null;
        if (mainUI
                .session()
                .getSettings()
                .getBooleanSetting(edu.missouristate.mars.Settings.EXCEPTION_HANDLER_ENABLED)
            && mainUI.session().getSettings().getExceptionHandler() != null
            && !mainUI.session().getSettings().getExceptionHandler().isEmpty()) {
          exceptionHandler = mainUI.session().getSettings().getExceptionHandler();
        }
        MIPSprogramsToAssemble =
            mainUI
                .session()
                .getProgram()
                .prepareFilesForAssembly(
                    filesToAssemble, mainUI.editorState().getFile().getPath(), exceptionHandler);
        mainUI.messagesPane.postMarsMessage(
            buildFileNameList(name + ": assembling ", MIPSprogramsToAssemble));
        // added logic to receive any warnings and output them.... DPS 11/28/06
        ErrorList warnings =
            mainUI
                .session()
                .getProgram()
                .assemble(MIPSprogramsToAssemble, extendedAssemblerEnabled, warningsAreErrors);
        if (warnings.warningsOccurred()) {
          mainUI.messagesPane.postMarsMessage(warnings.generateWarningReport());
        }
        mainUI.messagesPane.postMarsMessage(name + ": operation completed successfully.\n\n");
        mainUI.editorState().setAssembled(true);
        mainUI.editorState().set(FileStatus.RUNNABLE);
        mainUI.session().resetRegisters();
        mainUI.session().context().coprocessor1.resetRegisters();
        mainUI.session().context().coprocessor0.resetRegisters();
        executePane.getTextSegmentWindow().setupTable();
        executePane.getDataSegmentWindow().setupTable();
        executePane
            .getDataSegmentWindow()
            .highlightCellForAddress(mainUI.session().memory.dataBaseAddress);
        executePane.getDataSegmentWindow().clearHighlighting();
        executePane.getLabelsWindow().setupTable();
        executePane.getTextSegmentWindow().setCodeHighlighting(true);
        executePane.getTextSegmentWindow().highlightStepAtPC();
        registersPane.getRegistersWindow().clearWindow();
        registersPane.getCoprocessor1Window().clearWindow();
        registersPane.getCoprocessor0Window().clearWindow();
        mainUI.setReset(true);
        mainUI.setStarted(false);
        mainUI.getMainPane().setSelectedComponent(executePane);

        // Aug. 24, 2005 Ken Vollmar
        mainUI
            .session()
            .context()
            .syscallEnvironment
            .close(); // Ensure that I/O "file descriptors" are initialized for a new program
        // run

      } catch (ProcessingException pe) {
        String errorReport = pe.errors().generateErrorAndWarningReport();
        mainUI.messagesPane.postMarsMessage(errorReport);
        mainUI.messagesPane.postMarsMessage(name + ": operation completed with errors.\n\n");
        // Select editor line containing first error, and corresponding error message.
        ArrayList<ErrorMessage> errorMessages = pe.errors().getErrorMessages();
        for (ErrorMessage em : errorMessages) {
          // No line or position may mean File Not Found (e.g. exception file). Don't try to open.
          // DPS 3-Oct-2010
          if (em.getLine() == 0 && em.getPosition() == 0) {
            continue;
          }
          if (!em.isWarning() || warningsAreErrors) {
            mainUI
                .getMessagesPane()
                .selectErrorMessage(em.getFilename(), em.getLine(), em.getPosition());
            // Bug workaround: Line selection does not work correctly for the JEditTextArea editor
            // when the file is opened then automatically assembled (assemble-on-open setting).
            // Automatic assemble happens in EditTabbedPane's openFile() method, by invoking
            // this method (actionPerformed) explicitly with null argument.  Thus e!=null test.
            // DPS 9-Aug-2010
            if (e != null) {
              mainUI
                  .getMessagesPane()
                  .selectEditorTextLine(em.getFilename(), em.getLine(), em.getPosition());
            }
            break;
          }
        }
        mainUI.editorState().setAssembled(false);
        mainUI.editorState().set(FileStatus.NOT_EDITED);
      }
    }
  }

  // Handy little utility for building comma-separated list of filenames
  // while not letting line length get out of hand.
  private String buildFileNameList(String preamble, ArrayList<MIPSprogram> programList) {
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
