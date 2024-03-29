package edu.missouristate.mars.mips.hardware;

import edu.missouristate.mars.Globals;
import org.jetbrains.annotations.Nullable;

import java.util.Observer;

/**
 * Represents Coprocessor 0.  We will use only its interrupt/exception registers.
 *
 * @author Pete Sanderson
 * @version August 2005
 **/

public class Coprocessor0 {
    /**
     * Coprocessor register names
     */
    public static final int VADDR = 8;
    public static final int STATUS = 12;
    public static final int CAUSE = 13;
    public static final int EPC = 14;

    public static final int EXCEPTION_LEVEL = 1;  // bit position in STATUS register
    // bits 8-15 (mask for interrupt levels) all set, bit 4 (user mode) set,
    // bit 1 (exception level) not set, bit 0 (interrupt enable) set.
    public static final int DEFAULT_STATUS_VALUE = 0x0000FF11;

    // NOTICE! POSSIBLE BREAKING CHANGE! Register names no longer have parentheses in them, which makes them accessible.
    private static final Register[] registers =
            {new Register("$vaddr", 8, 0),
                    new Register("$status", 12, DEFAULT_STATUS_VALUE),
                    new Register("$cause", 13, 0),
                    new Register("$epc", 14, 0)
            };


    /**
     * Method for displaying the register values for debugging.
     **/

    public static void showRegisters() {
        for (Register register : registers) {
            System.out.println("Name: " + register.getName());
            System.out.println("Number: " + register.getNumber());
            System.out.println("Value: " + register.getValue());
            System.out.println();
        }
    }

    /**
     * Sets the value of the register given to the value given.
     *
     * @param n   name of register to set the value of ($n, where n is reg number).
     * @param val The desired value for the register.
     * @return old value in register prior to update
     **/

    public static int updateRegister(String n, int val) {
        int oldValue = 0;
        for (Register register : registers) {
            if (("$" + register.getNumber()).equals(n) || register.getName().equals(n)) {
                oldValue = register.getValue();
                register.setValue(val);
                break;
            }
        }
        return oldValue;
    }

    /**
     * This method updates the register value who's number is num.
     *
     * @param num Number of register to set the value of.
     * @param val The desired value for the register.
     * @return old value in register prior to update
     **/
    public static int updateRegister(int num, int val) {
        int old = 0;
        for (Register register : registers) {
            if (register.getNumber() == num) {
                old = (Globals.getSettings().getBackSteppingEnabled())
                        ? Globals.program.getBackStepper().addCoprocessor0Restore(num, register.setValue(val))
                        : register.setValue(val);
                break;
            }
        }
        return old;
    }


    /**
     * Returns the value of the register who's number is num.
     *
     * @param num The register number.
     * @return The value of the given register. Zero for non-implemented registers
     **/

    public static int getValue(int num) {
        for (Register register : registers) {
            if (register.getNumber() == num) {
                return register.getValue();
            }
        }
        return 0;
    }

    /**
     * For getting the number representation of the register.
     *
     * @param n The string formatted register name to look for.
     * @return The number of the register represented by the string. -1 if no match.
     **/

    public static int getNumber(String n) {
        for (Register register : registers) {
            if (("$" + register.getNumber()).equals(n) || register.getName().equals(n)) {
                return register.getNumber();
            }
        }
        return -1;
    }

    /**
     * For returning the set of registers.
     *
     * @return The set of registers.
     **/

    public static Register[] getRegisters() {
        return registers;
    }


    /**
     * Coprocessor0 implements only selected registers, so the register number
     * (8, 12, 13, 14) does not correspond to its position in the list of registers
     * (0, 1, 2, 3).
     *
     * @param r A coprocessor0 Register
     * @return the list position of given register, -1 if not found.
     **/

    public static int getRegisterPosition(Register r) {
        for (int i = 0; i < registers.length; i++) {
            if (registers[i] == r) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get register object corresponding to given name.  If no match, return null.
     *
     * @param rname The register name,  in $0 format.
     * @return The register object,or null if not found.
     **/

    public static @Nullable Register getRegister(String rname) {
        for (Register register : registers) {
            if (("$" + register.getNumber()).equals(rname) || register.getName().equals(rname)) {
                return register;
            }
        }
        return null;
    }


    /**
     * Method to reinitialize the values of the registers.
     **/

    public static void resetRegisters() {
        for (Register register : registers) {
            register.resetValue();
        }
    }

    /**
     * Each register is a separate object and Observable.  This handy method
     * will add the given Observer to each one.
     */
    public static void addRegisterObserver(Observer observer) {
        for (Register register : registers) {
            register.addObserver(observer);
        }
    }

    /**
     * Each register is a separate object and Observable.  This handy method
     * will delete the given Observer from each one.
     */
    public static void deleteRegisterObserver(Observer observer) {
        for (Register register : registers) {
            register.deleteObserver(observer);
        }
    }
}
