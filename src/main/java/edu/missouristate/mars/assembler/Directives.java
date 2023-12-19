package edu.missouristate.mars.assembler;

import java.util.ArrayList;

/**
 * Class representing MIPS assembler directives.  If Java had enumerated types, these
 * would probably be implemented that way.  Each directive is represented by a unique object.
 * The directive name is indicative of the directive it represents.  For example, DATA
 * represents the MIPS .data directive.
 *
 * @author Pete Sanderson
 * @version August 2003
 **/
public final class Directives {
    private static final ArrayList<Directives> directiveList = new ArrayList<>();
    public static final Directives DATA = new Directives(
        ".data",
        "Subsequent items stored in Data segment at next available address"
    );
    public static final Directives TEXT = new Directives(
        ".text",
        "Subsequent items (instructions) stored in Text segment at next available address"
    );
    public static final Directives WORD = new Directives(
        ".word",
        "Store the listed value(s) as 32 bit words on word boundary"
    );
    public static final Directives ASCII = new Directives(
        ".ascii",
        "Store the string in the Data segment but do not add null terminator"
    );
    public static final Directives ASCIIZ = new Directives(
        ".asciiz",
        "Store the string in the Data segment and add null terminator"
    );
    public static final Directives BYTE = new Directives(
        ".byte",
        "Store the listed value(s) as 8 bit bytes"
    );
    public static final Directives ALIGN = new Directives(
            ".align",
            "Align next data item on specified byte boundary (0=byte, 1=half, 2=word, 3=double)"
    );
    public static final Directives HALF = new Directives(
            ".half",
            "Store the listed value(s) as 16 bit halfwords on halfword boundary"
    );
    public static final Directives SPACE = new Directives(
            ".space",
            "Reserve the next specified number of bytes in Data segment"
    );
    public static final Directives DOUBLE = new Directives(
            ".double",
            "Store the listed value(s) as double precision floating point"
    );
    public static final Directives FLOAT = new Directives(
            ".float",
            "Store the listed value(s) as single precision floating point"
    );
    public static final Directives EXTERN = new Directives(
            ".extern",
            "Declare the listed label and byte length to be a global data field"
    );
    public static final Directives KDATA = new Directives(
            ".kdata",
            "Subsequent items stored in Kernel Data segment at next available address"
    );
    public static final Directives KTEXT = new Directives(
            ".ktext",
            "Subsequent items (instructions) stored in Kernel Text segment at next available address"
    );
    public static final Directives GLOBL = new Directives(
            ".globl",
            "Declare the listed label(s) as global to enable referencing from other files"
    );
    public static final Directives SET = new Directives(
            ".set",
            "Set assembler variables.  Currently ignored but included for SPIM compatability"
    );
    /*  EQV added by DPS 11 July 2012 */
    public static final Directives EQV = new Directives(
            ".eqv",
            "Substitute second operand for first. First operand is symbol, second operand is expression (like #define)"
    );
    /* MACRO and END_MACRO added by Mohammad Sekhavat Oct 2012 */
    public static final Directives MACRO = new Directives(
            ".macro",
            "Begin macro definition.  See .end_macro"
    );
    public static final Directives END_MACRO = new Directives(
            ".end_macro",
            "End macro definition.  See .macro"
    );
    /*  INCLUDE added by DPS 11 Jan 2013 */
    public static final Directives INCLUDE = new Directives(
            ".include",
            "Insert the contents of the specified file.  Put filename in quotes."
    );

    private final String descriptor;
    private final String description; // help text

    private Directives() {
        // private ctor assures no objects can be created other than those above.
        this.descriptor = "generic";
        this.description = "";
        directiveList.add(this);
    }

    private Directives(String name, String description) {
        this.descriptor = name;
        this.description = description;
        directiveList.add(this);
    }

    /**
     * Find Directive object, if any, which matches the given String.
     *
     * @param str A String containing candidate directive name (e.g. ".ascii")
     * @return If match is found, returns matching Directives object, else returns <tt>null</tt>.
     **/
    public static Directives matchDirective(String str) {
        Directives match;
        for (Directives directives : directiveList) {
            match = directives;
            if (str.equalsIgnoreCase(match.descriptor)) {
                return match;
            }
        }
        return null;
    }

    /**
     * Find Directive object, if any, which contains the given string as a prefix. For example,
     * ".a" will match ".ascii", ".asciiz" and ".align"
     *
     * @param str A String
     * @return If match is found, returns ArrayList of matching Directives objects, else returns <tt>null</tt>.
     **/
    public static ArrayList<Directives> prefixMatchDirectives(String str) {
        ArrayList<Directives> matches = null;
        for (Directives directives : directiveList) {
            if (directives.descriptor.toLowerCase().startsWith(str.toLowerCase())) {
                if (matches == null) {
                    matches = new ArrayList<>();
                }
                matches.add(directives);
            }
        }
        return matches;
    }

    /**
     * Produces String-ified version of Directive object
     *
     * @return String representing Directive: its MIPS name
     **/
    public String toString() {
        return this.descriptor;
    }

    /**
     * Get name of this Directives object
     *
     * @return name of this MIPS directive as a String
     **/
    public String getName() {
        return this.descriptor;
    }

    /**
     * Get description of this Directives object
     *
     * @return description of this MIPS directive (for help purposes)
     **/
    public String getDescription() {
        return this.description;
    }

    /**
     * Produces List of Directive objects
     *
     * @return MIPS Directive
     **/
    public static ArrayList<Directives> getDirectiveList() {
        return directiveList;
    }

    /**
     * Lets you know whether given directive is for integer (WORD,HALF,BYTE).
     *
     * @param direct a MIPS directive
     * @return true if given directive is FLOAT or DOUBLE, false otherwise
     **/
    public static boolean isIntegerDirective(Directives direct) {
        return direct == Directives.WORD || direct == Directives.HALF || direct == Directives.BYTE;
    }

    /**
     * Lets you know whether given directive is for floating number (FLOAT,DOUBLE).
     *
     * @param direct a MIPS directive
     * @return true if given directive is FLOAT or DOUBLE, false otherwise.
     **/
    public static boolean isFloatingDirective(Directives direct) {
        return direct == Directives.FLOAT || direct == Directives.DOUBLE;
    }
}