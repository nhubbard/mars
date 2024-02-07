package edu.missouristate.mars.tools;//.bhtsim;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.Vector;

/**
 * Represents the GUI of the BHT Simulator Tool.
 * <p>
 * <p>
 * The GUI consists of mainly four parts:
 * <ul>
 * <li>A configuration panel to select the number of entries and the history size
 * <li>A information panel that displays the most recent branch instruction including its address and BHT index
 * <li>A table representing the BHT with all entries and their internal state and statistics
 * <li>A log panel that summarizes the predictions in a textual form
 * </ul>
 *
 * @author ingo.kofler@itec.uni-klu.ac.at
 */
//@SuppressWarnings("serial")
public class BHTSimGUI extends JPanel {

    /**
     * text field presenting the most recent branch instruction
     */
    private JTextField instructionField;

    /**
     * text field representing the address of the most recent branch instruction
     */
    private JTextField addressField;

    /**
     * text field representing the resulting BHT index of the branch instruction
     */
    private JTextField indexField;

    /**
     * combo box for selecting the number of BHT entries
     */
    private JComboBox<Integer> bhtEntriesBox;

    /**
     * combo box for selecting the history size
     */
    private JComboBox<Integer> historySizeBox;

    /**
     * combo box for selecting the initial value
     */
    private JComboBox<String> initialBHTSizeBox;

    /**
     * the table representing the BHT
     */
    private final JTable bhtTable;

    /**
     * text field for log output
     */
    private JTextArea logArea;

    /**
     * constant for the color that highlights the current BHT entry
     */
    public final static Color COLOR_PRE_PREDICTION = Color.yellow;

    /**
     * constant for the color to signal a correct prediction
     */
    public final static Color COLOR_PREDICTION_CORRECT = Color.green;

    /**
     * constant for the color to signal a misprediction
     */
    public final static Color COLOR_PREDICTION_INCORRECT = Color.red;

    /**
     * constant for the String representing "take the branch"
     */
    public final static String BHT_TAKE_BRANCH = "TAKE";

    /**
     * constant for the String representing "do not take the branch"
     */
    public final static String BHT_DO_NOT_TAKE_BRANCH = "NOT TAKE";


    /**
     * Creates the GUI components of the BHT Simulator
     * The GUI is a subclass of JPanel which is integrated in the GUI of the MARS tool
     */
    public BHTSimGUI() {
        BorderLayout layout = new BorderLayout();
        layout.setVgap(10);
        layout.setHgap(10);
        setLayout(layout);

        bhtTable = createAndInitTable();

        add(buildConfigPanel(), BorderLayout.NORTH);
        add(buildInfoPanel(), BorderLayout.WEST);
        add(new JScrollPane(bhtTable), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);
    }

    /**
     * Creates and initializes the JTable representing the BHT.
     *
     * @return the JTable representing the BHT
     */
    private JTable createAndInitTable() {
        // create the table
        JTable theTable = new JTable();

        // create a default renderer for double values (percentage)
        DefaultTableCellRenderer doubleRenderer = new DefaultTableCellRenderer() {
            private final DecimalFormat formatter = new DecimalFormat("##0.00");

            public void setValue(Object value) {
                setText((value == null) ? "" : formatter.format(value));
            }
        };
        doubleRenderer.setHorizontalAlignment(SwingConstants.CENTER);

        // create a default renderer for all other values with center alignment
        DefaultTableCellRenderer defRenderer = new DefaultTableCellRenderer();
        defRenderer.setHorizontalAlignment(SwingConstants.CENTER);

        theTable.setDefaultRenderer(Double.class, doubleRenderer);
        theTable.setDefaultRenderer(Integer.class, defRenderer);
        theTable.setDefaultRenderer(String.class, defRenderer);

        theTable.setSelectionBackground(BHTSimGUI.COLOR_PRE_PREDICTION);
        theTable.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);

        return theTable;

    }


    /**
     * Creates and initializes the panel holding the instruction, address and index text fields.
     *
     * @return the info panel
     */
    private JPanel buildInfoPanel() {
        instructionField = new JTextField();
        addressField = new JTextField();
        indexField = new JTextField();

        instructionField.setColumns(10);
        instructionField.setEditable(false);
        instructionField.setHorizontalAlignment(JTextField.CENTER);
        addressField.setColumns(10);
        addressField.setEditable(false);
        addressField.setHorizontalAlignment(JTextField.CENTER);
        indexField.setColumns(10);
        indexField.setEditable(false);
        indexField.setHorizontalAlignment(JTextField.CENTER);

        JPanel panel = new JPanel();
        JPanel outerPanel = new JPanel();
        outerPanel.setLayout(new BorderLayout());

        GridBagLayout gbl = new GridBagLayout();
        panel.setLayout(gbl);

        GridBagConstraints c = new GridBagConstraints();

        c.insets = new Insets(5, 5, 2, 5);
        c.gridx = 1;
        c.gridy = 1;

        panel.add(new JLabel("Instruction"), c);
        c.gridy++;
        panel.add(instructionField, c);
        c.gridy++;
        panel.add(new JLabel("@ Address"), c);
        c.gridy++;
        panel.add(addressField, c);
        c.gridy++;
        panel.add(new JLabel("-> Index"), c);
        c.gridy++;
        panel.add(indexField, c);

        outerPanel.add(panel, BorderLayout.NORTH);
        return outerPanel;
    }


    /**
     * Creates and initializes the panel for the configuration of the tool
     * The panel contains two combo boxes for selecting the number of BHT entries and the history size.
     *
     * @return a panel for the configuration
     */
    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel();

        Vector<Integer> sizes = new Vector<>();
        sizes.add(8);
        sizes.add(16);
        sizes.add(32);

        Vector<Integer> bits = new Vector<>();
        bits.add(1);
        bits.add(2);

        Vector<String> initVals = new Vector<>();
        initVals.add(BHTSimGUI.BHT_DO_NOT_TAKE_BRANCH);
        initVals.add(BHTSimGUI.BHT_TAKE_BRANCH);

        bhtEntriesBox = new JComboBox<>(sizes);
        historySizeBox = new JComboBox<>(bits);
        initialBHTSizeBox = new JComboBox<>(initVals);

        panel.add(new JLabel("# of BHT entries"));
        panel.add(bhtEntriesBox);
        panel.add(new JLabel("BHT history size"));
        panel.add(historySizeBox);
        panel.add(new JLabel("Initial value"));
        panel.add(initialBHTSizeBox);

        return panel;
    }


    /**
     * Creates and initializes the panel containing the log text area.
     *
     * @return the panel for the logging output
     */
    private JPanel buildLogPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        logArea = new JTextArea();
        logArea.setRows(6);
        logArea.setEditable(false);

        panel.add(new JLabel("Log"), BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        return panel;
    }


    /***
     * Returns the combo box for selecting the number of BHT entries.
     *
     * @return the reference to the combo box
     */
    public JComboBox<Integer> getCbBHTentries() {
        return bhtEntriesBox;
    }


    /***
     * Returns the combo box for selecting the size of the BHT history.
     *
     * @return the reference to the combo box
     */
    public JComboBox<Integer> getCbBHThistory() {
        return historySizeBox;
    }


    /***
     * Returns the combo box for selecting the initial value of the BHT
     *
     * @return the reference to the combo box
     */
    public JComboBox<String> getCbBHTinitVal() {
        return initialBHTSizeBox;
    }

    /***
     * Returns the table representing the BHT.
     *
     * @return the reference to the table
     */
    public JTable getTabBHT() {
        return bhtTable;
    }


    /***
     * Returns the text area for log purposes.
     *
     * @return the reference to the text area
     */
    public JTextArea getTaLog() {
        return logArea;
    }


    /***
     * Returns the text field for displaying the most recent branch instruction
     *
     * @return the reference to the text field
     */
    public JTextField getTfInstruction() {
        return instructionField;
    }


    /***
     * Returns the text field for displaying the address of the most recent branch instruction
     *
     * @return the reference to the text field
     */
    public JTextField getTfAddress() {
        return addressField;
    }


    /***
     * Returns the text field for displaying the corresponding index into the BHT
     *
     * @return the reference to the text field
     */
    public JTextField getTfIndex() {
        return indexField;
    }


}
