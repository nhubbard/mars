package edu.missouristate.mars.assembler;

/**
 * This interface is intended for use by ExtendedInstruction objects to define, using
 * the translate() method, how to translate the extended (pseudo) instruction into
 * a sequence of one or more basic instructions, which can then be translated into
 * binary machine code.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public interface TranslationCode {
    /**
     * This is a callback method defined in anonymous class specified as
     * argument to ExtendedInstruction constructor.  It is called when
     * assembler finds a program statement matching that ExtendedInstruction,
     */
    void translate();
}
