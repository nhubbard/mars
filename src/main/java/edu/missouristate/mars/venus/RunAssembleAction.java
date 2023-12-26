package edu.missouristate.mars.venus;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.*;
import edu.missouristate.mars.mips.hardware.*;

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
    static ArrayList<MIPSProgram> getMIPSProgramsToAssemble() {
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
        extendedAssemblerEnabled = Globals.getSettings().getBooleanSetting(Settings.EXTENDED_ASSEMBLER_ENABLED);
        warningsAreErrors = Globals.getSettings().getBooleanSetting(Settings.WARNINGS_ARE_ERRORS);
        if (FileStatus.getFile() != null) {
            if (FileStatus.get() == FileStatus.EDITED) {
                mainUI.editor.save();
            }
            try {
                Globals.program = new MIPSProgram();
                ArrayList<String> filesToAssemble;
                if (Globals.getSettings().getBooleanSetting(Settings.ASSEMBLE_ALL_ENABLED)) {// setting calls for multiple file assembly
                    filesToAssemble = FilenameFinder.getFilenameList(
                            new File(FileStatus.getName()).getParent(), Globals.getFileExtensions());
                } else {
                    filesToAssemble = new ArrayList<>();
                    filesToAssemble.add(FileStatus.getName());
                }
                String exceptionHandler = null;
                if (Globals.getSettings().getBooleanSetting(Settings.EXCEPTION_HANDLER_ENABLED) &&
                        Globals.getSettings().getExceptionHandler() != null &&
                        !Globals.getSettings().getExceptionHandler().isEmpty()) {
                    exceptionHandler = Globals.getSettings().getExceptionHandler();
                }
                MIPSProgramsToAssemble = Globals.program.prepareFilesForAssembly(filesToAssemble, FileStatus.getFile().getPath(), exceptionHandler);
                mainUI.messagesPane.postMarsMessage(buildFileNameList(name + ": assembling ", MIPSProgramsToAssemble));
                // added logic to receive any warnings and output them.... DPS 11/28/06
                ErrorList warnings = Globals.program.assemble(MIPSProgramsToAssemble, extendedAssemblerEnabled,
                        warningsAreErrors);
                if (warnings.hasWarnings()) {
                    mainUI.messagesPane.postMarsMessage(warnings.generateReport(true));
                }
                mainUI.messagesPane.postMarsMessage(
                        name + ": operation completed successfully.\n\n");
                FileStatus.setAssembled(true);
                FileStatus.set(FileStatus.RUNNABLE);
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
                mainUI.messagesPane.postMarsMessage(errorReport);
                mainUI.messagesPane.postMarsMessage(
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
                FileStatus.set(FileStatus.NOT_EDITED);
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
