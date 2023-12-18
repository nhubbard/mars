package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.Binary;

import java.util.*;

/**
 * Creats a table of Symbol objects.
 *
 * @author Jason Bumgarner, Jason Shrewsbury
 * @version June 2003
 **/

public class SymbolTable {
    private static final String startLabel = "main";
    private final String filename;
    private ArrayList<Symbol> table;
    // Note -1 is legal 32 bit address (0xFFFFFFFF) but it is the high address in
    // kernel address space so highly unlikely that any symbol will have this as
    // its associated address!
    public static final int NOT_FOUND = -1;

    /**
     * Create a new empty symbol table for given file
     *
     * @param filename name of file this symbol table is associated with.  Will be
     *                 used only for output/display so it can be any descriptive string.
     */
    public SymbolTable(String filename) {
        this.filename = filename;
        this.table = new ArrayList<>();
    }

    /**
     * Adds a Symbol object into the array of Symbols.
     *
     * @param token   The token representing the Symbol.
     * @param address The address of the Symbol.
     * @param b       The type of Symbol, true for data, false for text.
     * @param errors  List to which to add any processing errors that occur.
     **/

    public void addSymbol(Token token, int address, boolean b, ErrorList errors) {
        String label = token.getValue();
        if (getSymbol(label) != null) {
            errors.add(new ErrorMessage(token.getSourceMIPSProgram(), token.getSourceLine(), token.getStartPos(), "label \"" + label + "\" already defined"));
        } else {
            Symbol s = new Symbol(label, address, b);
            table.add(s);
            if (Globals.debug)
                System.out.println("The symbol " + label + " with address " + address + " has been added to the " + this.filename + " symbol table.");
        }
    }


    /**
     * Removes a symbol from the Symbol table.  If not found, it does nothing.
     * This will rarely happen (only when variable is declared .globl after already
     * being defined in the local symbol table).
     *
     * @param token The token representing the Symbol.
     **/

    public void removeSymbol(Token token) {
        String label = token.getValue();
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).getName().equals(label)) {
                table.remove(i);
                if (Globals.debug)
                    System.out.println("The symbol " + label + " has been removed from the " + this.filename + " symbol table.");
                break;
            }
        }
    }


    /**
     * Method to return the address associated with the given label.
     *
     * @param s The label.
     * @return The memory address of the label given, or NOT_FOUND if not found in symbol table.
     **/
    public int getAddress(String s) {
        for (Symbol o : table) {
            if (o.getName().equals(s)) {
                return o.getAddress();
            }
        }
        return NOT_FOUND;
    }

    /**
     * Method to return the address associated with the given label.  Look first
     * in this (local) symbol table then in symbol table of labels declared
     * global (.globl directive).
     *
     * @param s The label.
     * @return The memory address of the label given, or NOT_FOUND if not found in symbol table.
     **/
    public int getAddressLocalOrGlobal(String s) {
        int address = this.getAddress(s);
        return (address == NOT_FOUND) ? Globals.symbolTable.getAddress(s) : address;
    }


    /**
     * Produce Symbol object from symbol table that corresponds to given String.
     *
     * @param s target String
     * @return Symbol object for requested target, null if not found in symbol table.
     **/

    public Symbol getSymbol(String s) {
        for (Symbol o : table) {
            if (o.getName().equals(s)) {
                return o;
            }
        }
        return null;
    }

    /**
     * Produce Symbol object from symbol table that has the given address.
     *
     * @param s String representing address
     * @return Symbol object having requested address, null if address not found in symbol table.
     **/

    public Symbol getSymbolGivenAddress(String s) {
        int address;
        try {
            address = Binary.stringToInt(s);// DPS 2-Aug-2010: was Integer.parseInt(s) but croaked on hex
        } catch (NumberFormatException e) {
            return null;
        }
        for (Symbol o : table) {
            if (o.getAddress() == address) {
                return o;
            }
        }
        return null;
    }

    /**
     * Produce Symbol object from either local or global symbol table that has the
     * given address.
     *
     * @param s String representing address
     * @return Symbol object having requested address, null if address not found in symbol table.
     **/
    public Symbol getSymbolGivenAddressLocalOrGlobal(String s) {
        Symbol sym = this.getSymbolGivenAddress(s);
        return (sym == null) ? Globals.symbolTable.getSymbolGivenAddress(s) : sym;
    }


    /**
     * For obtaining the Data Symbols.
     *
     * @return An ArrayList of Symbol objects.
     **/

    public ArrayList<Symbol> getDataSymbols() {
        ArrayList<Symbol> list = new ArrayList<>();
        for (Symbol o : table) {
            if (o.getType()) {
                list.add(o);
            }
        }
        return list;
    }


    /**
     * For obtaining the Text Symbols.
     *
     * @return An ArrayList of Symbol objects.
     **/

    public ArrayList<Symbol> getTextSymbols() {
        ArrayList<Symbol> list = new ArrayList<>();
        for (Symbol o : table) {
            if (!o.getType()) {
                list.add(o);
            }
        }
        return list;
    }

    /**
     * For obtaining all the Symbols.
     *
     * @return An ArrayList of Symbol objects.
     **/

    public ArrayList<Symbol> getAllSymbols() {
        return new ArrayList<>(table);
    }

    /**
     * Get the count of entries currently in the table.
     *
     * @return Number of symbol table entries.
     **/

    public int getSize() {
        return table.size();
    }

    /**
     * Creates a fresh arrayList for a new table.
     **/

    public void clear() {
        table = new ArrayList<>();
    }

    /**
     * Fix address in symbol table entry.  Any and all entries that match the original
     * address will be modified to contain the replacement address. There is no effect,
     * if none of the addresses matches.
     *
     * @param originalAddress    Address associated with 0 or more symtab entries.
     * @param replacementAddress Any entry that has originalAddress will have its
     *                           address updated to this value.  Does nothing if none do.
     */

    public void fixSymbolTableAddress(int originalAddress, int replacementAddress) {
        Symbol label = getSymbolGivenAddress(Integer.toString(originalAddress));
        while (label != null) {
            label.setAddress(replacementAddress);
            label = getSymbolGivenAddress(Integer.toString(originalAddress));
        }
    }

    /**
     * Fetches the text segment label (symbol) which, if declared global, indicates
     * the starting address for execution.
     *
     * @return String containing global label whose text segment address is starting address for program execution.
     **/
    public static String getStartLabel() {
        return startLabel;
    }
}