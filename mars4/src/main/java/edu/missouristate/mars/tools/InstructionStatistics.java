package edu.missouristate.mars.tools;

import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.mips.hardware.AccessNotice;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Memory;
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Observable;

/**
 * A MARS tool for obtaining instruction statistics by instruction category.
 * <p>
 * The code of this tools is initially based on the Instruction counter tool by Felipe Lassa.
 *
 * @author Ingo Kofler <ingo.kofler@itec.uni-klu.ac.at>
 */
public class InstructionStatistics extends AbstractMarsToolAndApplication {

    /**
     * name of the tool
     */
    private static final String NAME = "Instruction Statistics";

    /**
     * version and author information of the tool
     */
    private static final String VERSION = "Version 1.0 (Ingo Kofler)";

    /**
     * heading of the tool
     */
    private static final String HEADING = "";


    /**
     * number of instruction categories used by this tool
     */
    private static final int MAX_CATEGORY = 5;

    /**
     * constant for ALU instructions category
     */
    private static final int CATEGORY_ALU = 0;

    /**
     * constant for jump instructions category
     */
    private static final int CATEGORY_JUMP = 1;

    /**
     * constant for branch instructions category
     */
    private static final int CATEGORY_BRANCH = 2;

    /**
     * constant for memory instructions category
     */
    private static final int CATEGORY_MEM = 3;

    /**
     * constant for any other instruction category
     */
    private static final int CATEGORY_OTHER = 4;


    /**
     * text field for visualizing the total number of instructions processed
     */
    private JTextField totalCounterField;

    /**
     * array of text field - one for each instruction category
     */
    private JTextField[] instructionCounterFields;

    /**
     * array of progress pars - one for each instruction category
     */
    private JProgressBar[] instructionProgressBars;


    /**
     * counter for the total number of instructions processed
     */
    private int totalCounter = 0;

    /**
     * array of counter variables - one for each instruction category
     */
    private final int[] counters = new int[MAX_CATEGORY];

    /**
     * names of the instruction categories as array
     */
    private final String[] categoryLabels = {"ALU", "Jump", "Branch", "Memory", "Other"};


    // From Felipe Lessa's instruction counter.  Prevent double-counting of instructions 
    // which happens because 2 read events are generated.   
    /**
     * The last address we saw. We ignore it because the only way for a
     * program to execute twice the same instruction is to enter an infinite
     * loop, which is not insteresting in the POV of counting instructions.
     */
    protected int lastAddress = -1;

    /**
     * Simple constructor, likely used to run a stand-alone enhanced instruction counter.
     *
     * @param title   String containing title for title bar
     * @param heading String containing text for heading shown in upper part of window.
     */
    public InstructionStatistics(String title, String heading) {
        super(title, heading);
    }


    /**
     * Simple construction, likely used by the MARS Tools menu mechanism.
     */
    public InstructionStatistics() {
        super(InstructionStatistics.NAME + ", " + InstructionStatistics.VERSION, InstructionStatistics.HEADING);
    }


    /**
     * returns the name of the tool
     *
     * @return the tools's name
     */
    public String getName() {
        return NAME;
    }


    /**
     * creates the display area for the tool as required by the API
     *
     * @return a panel that holds the GUI of the tool
     */
    protected JComponent buildMainDisplayArea() {

        // Create GUI elements for the tool
        JPanel panel = new JPanel(new GridBagLayout());

        totalCounterField = new JTextField("0", 10);
        totalCounterField.setEditable(false);

        instructionCounterFields = new JTextField[MAX_CATEGORY];
        instructionProgressBars = new JProgressBar[MAX_CATEGORY];

        // for each category a text field and a progress bar is created
        for (int i = 0; i < InstructionStatistics.MAX_CATEGORY; i++) {
            instructionCounterFields[i] = new JTextField("0", 10);
            instructionCounterFields[i].setEditable(false);
            instructionProgressBars[i] = new JProgressBar(JProgressBar.HORIZONTAL);
            instructionProgressBars[i].setStringPainted(true);
        }

        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.gridheight = c.gridwidth = 1;

        // create the label and text field for the total instruction counter
        c.gridx = 2;
        c.gridy = 1;
        c.insets = new Insets(0, 0, 17, 0);
        panel.add(new JLabel("Total: "), c);
        c.gridx = 3;
        panel.add(totalCounterField, c);

        c.insets = new Insets(3, 3, 3, 3);

        // create label, text field and progress bar for each category
        for (int i = 0; i < InstructionStatistics.MAX_CATEGORY; i++) {
            c.gridy++;
            c.gridx = 2;
            panel.add(new JLabel(categoryLabels[i] + ":   "), c);
            c.gridx = 3;
            panel.add(instructionCounterFields[i], c);
            c.gridx = 4;
            panel.add(instructionProgressBars[i], c);
        }

        return panel;
    }


    /**
     * registers the tool as observer for the text segment of the MIPS program
     */
    protected void addAsObserver() {
        addAsObserver(Memory.textBaseAddress, Memory.textLimitAddress);
    }


    /**
     * decodes the instruction and determines the category of the instruction.
     * <p>
     * The instruction is decoded by extracting the operation and function code of the 32-bit instruction.
     * Only the most relevant instructions are decoded and categorized.
     *
     * @param stmt the instruction to decode
     * @return the category of the instruction
     * @see InstructionStatistics#CATEGORY_ALU
     * @see InstructionStatistics#CATEGORY_JUMP
     * @see InstructionStatistics#CATEGORY_BRANCH
     * @see InstructionStatistics#CATEGORY_MEM
     * @see InstructionStatistics#CATEGORY_OTHER
     */
    protected int getInstructionCategory(ProgramStatement stmt) {

        int opCode = stmt.getBinaryStatement() >>> (32 - 6);
        int funct = stmt.getBinaryStatement() & 0x1F;

        if (opCode == 0x00) {
            if (funct == 0x00)
                return InstructionStatistics.CATEGORY_ALU; // sll
            if (0x02 <= funct && funct <= 0x07)
                return InstructionStatistics.CATEGORY_ALU; // srl, sra, sllv, srlv, srav
            if (funct == 0x08 || funct == 0x09)
                return InstructionStatistics.CATEGORY_JUMP; // jr, jalr
            if (0x10 <= funct)
                return InstructionStatistics.CATEGORY_ALU; // mfhi, mthi, mflo, mtlo, mult, multu, div, divu, add, addu, sub, subu, and, or, xor, nor, slt, sltu
            return InstructionStatistics.CATEGORY_OTHER;
        }
        if (opCode == 0x01) {
            if (funct <= 0x07)
                return InstructionStatistics.CATEGORY_BRANCH; // bltz, bgez, bltzl, bgezl
            if (0x10 <= funct && funct <= 0x13)
                return InstructionStatistics.CATEGORY_BRANCH; // bltzal, bgezal, bltzall, bgczall
            return InstructionStatistics.CATEGORY_OTHER;
        }
        if (opCode == 0x02 || opCode == 0x03)
            return InstructionStatistics.CATEGORY_JUMP; // j, jal
        if (opCode <= 0x07)
            return InstructionStatistics.CATEGORY_BRANCH; // beq, bne, blez, bgtz
        if (opCode <= 0x0F)
            return InstructionStatistics.CATEGORY_ALU; // addi, addiu, slti, sltiu, andi, ori, xori, lui
        if (0x14 <= opCode && opCode <= 0x17)
            return InstructionStatistics.CATEGORY_BRANCH; // beql, bnel, blezl, bgtzl
        if (0x20 <= opCode && opCode <= 0x26)
            return InstructionStatistics.CATEGORY_MEM; // lb, lh, lwl, lw, lbu, lhu, lwr
        if (0x28 <= opCode && opCode <= 0x2E)
            return InstructionStatistics.CATEGORY_MEM; // sb, sh, swl, sw, swr

        return InstructionStatistics.CATEGORY_OTHER;
    }


    /**
     * method that is called each time the MIPS simulator accesses the text segment.
     * Before an instruction is executed by the simulator, the instruction is fetched from the program memory.
     * This memory access is observed and the corresponding instruction is decoded and categorized by the tool.
     * According to the category the counter values are increased and the display gets updated.
     *
     * @param resource the observed resource
     * @param notice   signals the type of access (memory, register etc.)
     */
    protected void processMIPSUpdate(Observable resource, AccessNotice notice) {

        if (!notice.accessIsFromMIPS())
            return;

        // check for a read access in the text segment
        if (notice.getAccessType() == AccessNotice.READ && notice instanceof MemoryAccessNotice memAccNotice) {

            // now it is safe to make a cast of the notice

            // The next three statments are from Felipe Lessa's instruction counter.  Prevents double-counting.
            int a = memAccNotice.getAddress();
            if (a == lastAddress)
                return;
            lastAddress = a;

            try {

                // access the statement in the text segment without notifying other tools etc.
                ProgramStatement stmt = Memory.getInstance().getStatementNoNotify(memAccNotice.getAddress());

                // necessary to handle possible null pointers at the end of the program
                // (e.g., if the simulator tries to execute the next instruction after the last instruction in the text segment)
                if (stmt != null) {
                    int category = getInstructionCategory(stmt);

                    totalCounter++;
                    counters[category]++;
                    updateDisplay();
                }
            } catch (AddressErrorException e) {
                // silently ignore these exceptions
            }
        }
    }


    /**
     * performs initialization tasks of the counters before the GUI is created.
     */
    protected void initializePreGUI() {
        totalCounter = 0;
        lastAddress = -1; // from Felipe Lessa's instruction counter tool
        Arrays.fill(counters, 0);
    }


    /**
     * resets the counter values of the tool and updates the display.
     */
    protected void reset() {
        totalCounter = 0;
        lastAddress = -1; // from Felipe Lessa's instruction counter tool
        Arrays.fill(counters, 0);
        updateDisplay();
    }


    /**
     * updates the text fields and progress bars according to the current counter values.
     */
    protected void updateDisplay() {
        totalCounterField.setText(String.valueOf(totalCounter));

        for (int i = 0; i < InstructionStatistics.MAX_CATEGORY; i++) {
            instructionCounterFields[i].setText(String.valueOf(counters[i]));
            instructionProgressBars[i].setMaximum(totalCounter);
            instructionProgressBars[i].setValue(counters[i]);
        }
    }
}
