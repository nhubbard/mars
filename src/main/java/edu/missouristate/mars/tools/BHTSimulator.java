package edu.missouristate.mars.tools;

import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.*;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Observable;

/**
 * A MARS tool for simulating branch prediction with a Branch History Table (BHT)
 * <p>
 * The simulation is based on observing the access to the instruction memory area (text segment).
 * If a branch instruction is encountered, a prediction based on a BHT is performed.
 * The outcome of the branch is compared with the prediction and the prediction is updated accordingly.
 * Statistics about the correct and incorrect number of predictions can be obtained for each BHT entry.
 * The number of entries in the BHT and the history that is considered for each prediction can be configured interactively.
 * A change of the configuration however causes a re-initialization of the BHT.
 * <p>
 * The tool can be used to show how branch prediction works in case of loops and how effective such simple methods are.
 * In case of nested loops the difference of BHT with one or two bit history can be explored and visualized.
 *
 * @author ingo.kofler@itec.uni-klu.ac.at
 */
public class BHTSimulator extends AbstractMarsToolAndApplication implements ActionListener {

    /**
     * constant for the default size of the BHT
     */
    public static final int BHT_DEFAULT_SIZE = 16;

    /**
     * constant for the default history size
     */
    public static final int BHT_DEFAULT_HISTORY = 1;

    /**
     * constant for the default inital value
     */
    public static final boolean BHT_DEFAULT_INITVAL = false;

    /**
     * the name of the tool
     */
    public static final String BHT_NAME = "BHT Simulator";

    /**
     * the version of the tool
     */
    public static final String BHT_VERSION = "Version 1.0 (Ingo Kofler)";

    /**
     * the heading of the tool
     */
    public static final String BHT_HEADING = "Branch History Table Simulator";

    /**
     * the GUI of the BHT simulator
     */
    private BHTSimGUI gui;

    /**
     * the model of the BHT
     */
    private BHTableModel bhTableModel;

    /**
     * state variable that indicates that the last instruction was a branch instruction (if address != 0) or not (address == 0)
     */
    private int pendingBranchInstAddress;

    /**
     * state variable that signals if the last branch was taken
     */
    private boolean lastBranchTaken;

    /**
     * Creates a BHT Simulator with given name and heading.
     */
    public BHTSimulator() {
        super(BHTSimulator.BHT_NAME + ", " + BHTSimulator.BHT_VERSION, BHTSimulator.BHT_HEADING);
    }

    /**
     * Adds BHTSimulator as observer of the text segment.
     */
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
        addAsObserver(RegisterFile.getProgramCounter());
    }

    /**
     * Creates a GUI and initialize the GUI with the default values.
     */
    protected JComponent buildMainDisplayArea() {

        gui = new BHTSimGUI();
        bhTableModel = new BHTableModel(BHTSimulator.BHT_DEFAULT_SIZE, BHTSimulator.BHT_DEFAULT_HISTORY, BHT_DEFAULT_INITVAL);

        gui.getTabBHT().setModel(bhTableModel);
        gui.getCbBHThistory().setSelectedItem(BHTSimulator.BHT_DEFAULT_HISTORY);
        gui.getCbBHTentries().setSelectedItem(BHTSimulator.BHT_DEFAULT_SIZE);

        gui.getCbBHTentries().addActionListener(this);
        gui.getCbBHThistory().addActionListener(this);
        gui.getCbBHTinitVal().addActionListener(this);

        return gui;
    }

    /**
     * Returns the name of the tool.
     *
     * @return the tool's name as String
     */
    public String getName() {
        return BHTSimulator.BHT_NAME;
    }

    /**
     * Performs a reset of the simulator.
     * This causes the BHT to be reseted and the log messages to be cleared.
     */
    protected void reset() {
        resetSimulator();
    }

    /**
     * Handles the actions when selecting another value in one of the two combo boxes.
     * Selecting a different BHT size or history causes a reset of the simulator.
     */
    public void actionPerformed(ActionEvent event) {
        // change of the BHT size or BHT bit configuration
        // resets the simulator
        if (event.getSource() == gui.getCbBHTentries() || event.getSource() == gui.getCbBHThistory() || event.getSource() == gui.getCbBHTinitVal()) {
            resetSimulator();
        }
    }

    /**
     * Resets the simulator by clearing the GUI elements and resetting the BHT.
     */
    protected void resetSimulator() {
        gui.getTfInstruction().setText("");
        gui.getTfAddress().setText("");
        gui.getTfIndex().setText("");
        gui.getTaLog().setText("");
        bhTableModel.initBHT((Integer) gui.getCbBHTentries().getSelectedItem(),
                (Integer) gui.getCbBHThistory().getSelectedItem(),
                gui.getCbBHTinitVal().getSelectedItem().equals(BHTSimGUI.BHT_TAKE_BRANCH));

        pendingBranchInstAddress = 0;
        lastBranchTaken = false;
    }

    /**
     * Handles the execution branch instruction.
     * This method is called each time a branch instruction is executed.
     * Based on the address of the instruction the corresponding index into the BHT is calculated.
     * The prediction is obtained from the BHT at the calculated index and is visualized appropriately.
     *
     * @param stmt the branch statement that is executed
     */
    protected void handlePreBranchInst(ProgramStatement stmt) {

        String strStmt = stmt.getBasicAssemblyStatement();
        int address = stmt.getAddress();
        int idx = bhTableModel.getIdxForAddress(address);

        // update the GUI
        gui.getTfInstruction().setText(strStmt);
        gui.getTfAddress().setText("0x" + Integer.toHexString(address));
        gui.getTfIndex().setText("" + idx);

        // mark the affected BHT row
        gui.getTabBHT().setSelectionBackground(BHTSimGUI.COLOR_PRE_PREDICTION);
        gui.getTabBHT().addRowSelectionInterval(idx, idx);

        // add output to log
        gui.getTaLog().append("instruction " + strStmt + " at address 0x" + Integer.toHexString(address) + ", maps to index " + idx + "\n");
        gui.getTaLog().append("branches to address 0x" + BHTSimulator.extractBranchAddress(stmt) + "\n");
        gui.getTaLog().append("prediction is: " + (bhTableModel.getPredictionAtIdx(idx) ? "take" : "do not take") + "...\n");
        gui.getTaLog().setCaretPosition(gui.getTaLog().getDocument().getLength());

    }

    /**
     * Handles the execution of the branch instruction.
     * The correctness of the prediction is visualized in both the table and the log message area.
     * The BHT is updated based on the information if the branch instruction was taken or not.
     *
     * @param branchInstAddr the address of the branch instruction
     * @param branchTaken    the information if the branch is taken or not (determined in a step before)
     */
    protected void handleExecBranchInst(int branchInstAddr, boolean branchTaken) {

        // determine the index in the BHT for the branch instruction
        int idx = bhTableModel.getIdxForAddress(branchInstAddr);

        // check if the prediction is correct
        boolean correctPrediction = bhTableModel.getPredictionAtIdx(idx) == branchTaken;

        gui.getTabBHT().setSelectionBackground(correctPrediction ? BHTSimGUI.COLOR_PREDICTION_CORRECT : BHTSimGUI.COLOR_PREDICTION_INCORRECT);

        // add some output at the log
        gui.getTaLog().append("branch " + (branchTaken ? "taken" : "not taken") + ", prediction was " + (correctPrediction ? "correct" : "incorrect") + "\n\n");
        gui.getTaLog().setCaretPosition(gui.getTaLog().getDocument().getLength());

        // update the BHT -> causes refresh of the table
        bhTableModel.updatePredictionAtIdx(idx, branchTaken);
    }


    /**
     * Determines if the instruction is a branch instruction or not.
     *
     * @param stmt the statement to investigate
     * @return true, if stmt is a branch instruction, otherwise false
     */
    protected static boolean isBranchInstruction(ProgramStatement stmt) {

        int opCode = stmt.getBinaryStatement() >>> (32 - 6);
        int funct = stmt.getBinaryStatement() & 0x1F;

        if (opCode == 0x01) {
            if (funct <= 0x07) return true; //  bltz, bgez, bltzl, bgezl
            if (0x10 <= funct && funct <= 0x13) return true; // bltzal, bgezal, bltzall, bgczall
        }

        if (0x04 <= opCode && opCode <= 0x07) return true; // beq, bne, blez, bgtz
        return 0x14 <= opCode && opCode <= 0x17; // beql, bnel, blezl, bgtzl
    }


    /**
     * Checks if the branch instruction delivered as parameter will branch or not.
     *
     * @param stmt the branch instruction to be investigated
     * @return true if the branch will be taken, otherwise false
     */
    protected static boolean willBranch(ProgramStatement stmt) {
        int opCode = stmt.getBinaryStatement() >>> (32 - 6);
        int funct = stmt.getBinaryStatement() & 0x1F;
        int rs = stmt.getBinaryStatement() >>> (32 - 6 - 5) & 0x1F;
        int rt = stmt.getBinaryStatement() >>> (32 - 6 - 5 - 5) & 0x1F;

        int valRS = RegisterFile.getRegisters()[rs].getValue();
        int valRT = RegisterFile.getRegisters()[rt].getValue();


        if (opCode == 0x01) {
            switch (funct) {
                case 0x00:
                    return valRS < 0; // bltz
                case 0x01:
                    return valRS >= 0; // bgez
                case 0x02:
                    return valRS < 0; // bltzl
                case 0x03:
                    return valRS >= 0; // bgezl
            }
        }

        return switch (opCode) {
            case 0x04, 0x14 -> valRS == valRT;
            case 0x05, 0x15 -> valRS != valRT;
            case 0x06, 0x16 -> valRS <= 0;
            case 0x07, 0x17 -> valRS >= 0;
            default -> true;
        };

    }


    /**
     * Extracts the target address of the branch.
     * <p>
     * In MIPS the target address is encoded as 16-bit value.
     * The target address is encoded relative to the address of the instruction after the branch instruction
     *
     * @param stmt the branch instruction
     * @return the address of the instruction that is executed if the branch is taken
     */
    protected static int extractBranchAddress(ProgramStatement stmt) {
        short offset = (short) (stmt.getBinaryStatement() & 0xFFFF);
        return stmt.getAddress() + (offset << 2) + 4;
    }


    /**
     * Callback for text segment access by the MIPS simulator.
     * <p>
     * The method is called each time the text segment is accessed to fetch the next instruction.
     * If the next instruction to execute was a branch instruction, the branch prediction is performed and visualized.
     * In case the last instruction was a branch instruction, the outcome of the branch prediction is analyzed and visualized.
     *
     * @param resource the observed resource
     * @param notice   signals the type of access (memory, register etc.)
     */
    protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

        if (!notice.getAccessIsFromMIPS()) return;


        if (notice.getAccessType() == AccessNotice.AccessType.READ && notice instanceof MemoryAccessNotice memAccNotice) {

            // now it is safe to make a cast of the notice

            try {
                // access the statement in the text segment without notifying other tools etc.
                ProgramStatement stmt = Memory.getInstance().getStatementNoNotify(memAccNotice.getAddress());

                // necessary to handle possible null pointers at the end of the program
                // (e.g., if the simulator tries to execute the next instruction after the last instruction in the text segment)
                if (stmt != null) {

                    boolean clearTextFields = true;

                    // first, check if there's a pending branch to handle
                    if (pendingBranchInstAddress != 0) {
                        handleExecBranchInst(pendingBranchInstAddress, lastBranchTaken);
                        clearTextFields = false;
                        pendingBranchInstAddress = 0;
                    }


                    // if current instruction is branch instruction
                    if (BHTSimulator.isBranchInstruction(stmt)) {
                        handlePreBranchInst(stmt);
                        lastBranchTaken = willBranch(stmt);
                        pendingBranchInstAddress = stmt.getAddress();
                        clearTextFields = false;
                    }


                    // clear text fields and selection
                    if (clearTextFields) {
                        gui.getTfInstruction().setText("");
                        gui.getTfAddress().setText("");
                        gui.getTfIndex().setText("");
                        gui.getTabBHT().clearSelection();
                    }
                } else {
                    // check if there's a pending branch to handle
                    if (pendingBranchInstAddress != 0) {
                        handleExecBranchInst(pendingBranchInstAddress, lastBranchTaken);
                        pendingBranchInstAddress = 0;
                    }
                }
            } catch (AddressErrorException e) {
                // silently ignore these exceptions
            }

        }
    }
}
