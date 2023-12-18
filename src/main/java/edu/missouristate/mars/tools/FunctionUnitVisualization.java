package edu.missouristate.mars.tools;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class FunctionUnitVisualization extends JFrame {

    private final String instruction;
    private final int alu = 4;
    private int currentUnit;

    /**
     * Create the frame.
     */
    public FunctionUnitVisualization(String instruction, int functionalUnit) {
        this.instruction = instruction;
        //setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setBounds(100, 100, 840, 575);
        JPanel contentPane = new JPanel();
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        contentPane.setLayout(new BorderLayout(0, 0));
        setContentPane(contentPane);
        int aluControl = 3;
        int control = 2;
        int register = 1;
        if (functionalUnit == register) {
            currentUnit = register;
            UnitAnimation reg = new UnitAnimation(instruction, register);
            contentPane.add(reg);
            reg.startAnimation(instruction);
        } else if (functionalUnit == control) {
            currentUnit = control;
            UnitAnimation reg = new UnitAnimation(instruction, control);
            contentPane.add(reg);
            reg.startAnimation(instruction);
        } else if (functionalUnit == aluControl) {
            currentUnit = aluControl;
            UnitAnimation reg = new UnitAnimation(instruction, aluControl);
            contentPane.add(reg);
            reg.startAnimation(instruction);
        }

    }

    /**
     * Launch the application.
     */
    public void run() {
        try {
            FunctionUnitVisualization frame = new FunctionUnitVisualization(instruction, currentUnit);
            frame.setVisible(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
