package edu.missouristate.mars.venus;

import javax.swing.*;

/**
 * Contains tabbed areas in the UI to display register contents
 *
 * @author Sanderson
 * @version August 2005
 */

public class RegistersPane extends JTabbedPane {
    final RegistersWindow regsTab;
    final Coprocessor1Window cop1Tab;
    final Coprocessor0Window cop0Tab;

    /**
     * Constructor for the RegistersPane class.
     */

    public RegistersPane(VenusUI appFrame, RegistersWindow regs, Coprocessor1Window cop1,
                         Coprocessor0Window cop0) {
        super();
        regsTab = regs;
        cop1Tab = cop1;
        cop0Tab = cop0;
        regsTab.setVisible(true);
        cop1Tab.setVisible(true);
        cop0Tab.setVisible(true);
        this.addTab("Registers", regsTab);
        this.addTab("Coproc 1", cop1Tab);
        this.addTab("Coproc 0", cop0Tab);
        this.setToolTipTextAt(0, "CPU registers");
        this.setToolTipTextAt(1, "Coprocessor 1 (floating point unit) registers");
        this.setToolTipTextAt(2, "selected Coprocessor 0 (exceptions and interrupts) registers");
    }

    /**
     * Return component containing integer register set.
     *
     * @return integer register window
     */
    public RegistersWindow getRegistersWindow() {
        return regsTab;
    }

    /**
     * Return component containing coprocessor 1 (floating point) register set.
     *
     * @return floating point register window
     */
    public Coprocessor1Window getCoprocessor1Window() {
        return cop1Tab;
    }

    /**
     * Return component containing coprocessor 0 (exceptions) register set.
     *
     * @return exceptions register window
     */
    public Coprocessor0Window getCoprocessor0Window() {
        return cop0Tab;
    }
}