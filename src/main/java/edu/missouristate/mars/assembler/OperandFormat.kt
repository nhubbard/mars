package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.mips.instructions.*;

import java.util.*;

/**
 * Provides utility method related to MIPS operand formats.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public class OperandFormat {
    private OperandFormat() {}

    /**
     * Syntax test for correct match in both numbers and types of operands.
     *
     * @param candidateList List of tokens generated from programmer's MIPS statement.
     * @param inst The (resumably best matched) MIPS instruction.
     * @param errors ErrorList into which any error messages generated here will be added.
     *
     * @return Returns <tt>true</tt> if the programmer's statement matches the MIPS
     * specification, else returns <tt>false</tt>.
     */
    static boolean tokenOperandMatch(TokenList candidateList, Instruction inst, ErrorList errors) {
        if (!numOperandsCheck(candidateList, inst, errors)) return false;
        return operandTypeCheck(candidateList, inst, errors);
    }

    /**
     * If the candidate operator token matches more than one instruction mnemonic, then select
     * first such Instruction that has an exact operand match.  If none match,
     * return the first Instruction and let the client deal with operand mismatches.
     */
    static Instruction bestOperandMatch(TokenList tokenList, ArrayList<Instruction> instrMatches) {
        if (instrMatches == null) return null;
        if (instrMatches.size() == 1) return instrMatches.get(0);
        for (Instruction instruction : instrMatches)
            if (tokenOperandMatch(tokenList, instruction, new ErrorList())) return instruction;
        return instrMatches.get(0);
    }

    /**
     * Check to see if numbers of operands are correct and generate an error message if not.
     */
    private static boolean numOperandsCheck(TokenList cand, Instruction spec, ErrorList errors) {
        int numOperands = cand.size() - 1;
        int reqNumOperands = spec.getTokenList().size() - 1;
        Token operator = cand.get(0);
        if (numOperands == reqNumOperands) {
            return true;
        } else if (numOperands < reqNumOperands) {
            String mess = "Too few or incorrectly formatted operands. Expected: " + spec.getExampleFormat();
            generateMessage(operator, mess, errors);
        } else {
            String mess = "Too many or incorrectly formatted operands. Expected: " + spec.getExampleFormat();
            generateMessage(operator, mess, errors);
        }
        return false;
    }

    /**
     * Generate an error message if operand is not of the correct type for this operation & operand position
     */
    private static boolean operandTypeCheck(TokenList cand, Instruction spec, ErrorList errors) {
        Token candToken, specToken;
        TokenTypes candType, specType;
        for (int i = 1; i < spec.getTokenList().size(); i++) {
            candToken = cand.get(i);
            specToken = spec.getTokenList().get(i);
            candType = candToken.getType();
            specType = specToken.getType();
            /*
             Type mismatch is error EXCEPT when (1) spec calls for register name and candidate is
             register number, (2) spec calls for register number, candidate is register name and
             names are permitted, (3)spec calls for integer of specified max bit length and
             candidate is integer of smaller bit length.
             Type match is error when spec calls for register name, candidate is register name, and
             names are not permitted.
             added 2-July-2010 DPS
             Not an error if spec calls for identifier and candidate is operator, since operator names can be used as labels.
            */
            if (specType == TokenTypes.IDENTIFIER && candType == TokenTypes.OPERATOR) {
                Token replacement = new Token(
                    TokenTypes.IDENTIFIER,
                    candToken.getValue(),
                    candToken.getSourceMIPSProgram(),
                    candToken.getSourceLine(),
                    candToken.getStartPos()
                );
                cand.set(i, replacement);
                continue;
            }
            // end 2-July-2010 addition

            if ((specType == TokenTypes.REGISTER_NAME || specType == TokenTypes.REGISTER_NUMBER) &&
                    candType == TokenTypes.REGISTER_NAME) {
                if (Globals.getSettings().getBooleanSetting(Settings.BARE_MACHINE_ENABLED)) {
                    /*
                     On 10-Aug-2010, I noticed this cannot happen since the IDE provides no access
                     to this setting, whose default value is false.
                    */
                    generateMessage(candToken, "Use register number instead of name.  See Settings.", errors);
                    return false;
                } else continue;
            }
            if (specType == TokenTypes.REGISTER_NAME && candType == TokenTypes.REGISTER_NUMBER) continue;
            if ((specType == TokenTypes.INTEGER_16 && candType == TokenTypes.INTEGER_5)   ||
                (specType == TokenTypes.INTEGER_16U && candType == TokenTypes.INTEGER_5)  ||
                (specType == TokenTypes.INTEGER_32 && candType == TokenTypes.INTEGER_5)   ||
                (specType == TokenTypes.INTEGER_32 && candType == TokenTypes.INTEGER_16U) ||
                (specType == TokenTypes.INTEGER_32 && candType == TokenTypes.INTEGER_16)) continue;
            if (candType == TokenTypes.INTEGER_16U || candType == TokenTypes.INTEGER_16) {
                int temp = Binary.stringToInt(candToken.getValue());
                if (specType == TokenTypes.INTEGER_16 && candType == TokenTypes.INTEGER_16U &&
                    temp >= DataTypes.MIN_HALF_VALUE && temp <= DataTypes.MAX_HALF_VALUE) continue;
                if (specType == TokenTypes.INTEGER_16U && candType == TokenTypes.INTEGER_16 &&
                    temp >= DataTypes.MIN_UHALF_VALUE && temp <= DataTypes.MAX_UHALF_VALUE) continue;
            }
            if ((specType == TokenTypes.INTEGER_5 && candType == TokenTypes.INTEGER_16)       ||
                    (specType == TokenTypes.INTEGER_5 && candType == TokenTypes.INTEGER_16U)  ||
                    (specType == TokenTypes.INTEGER_5 && candType == TokenTypes.INTEGER_32)   ||
                    (specType == TokenTypes.INTEGER_16 && candType == TokenTypes.INTEGER_16U) ||
                    (specType == TokenTypes.INTEGER_16U && candType == TokenTypes.INTEGER_16) ||
                    (specType == TokenTypes.INTEGER_16U && candType == TokenTypes.INTEGER_32) ||
                    (specType == TokenTypes.INTEGER_16 && candType == TokenTypes.INTEGER_32)) {
                generateMessage(candToken, "operand is out of range", errors);
                return false;
            }
            if (candType != specType) {
                generateMessage(candToken, "operand is of incorrect type", errors);
                return false;
            }
        }
        return true;
    }

    /**
     * Handy utility for all parse errors.
     */
    private static void generateMessage(Token token, String mess, ErrorList errors) {
        errors.add(new ErrorMessage(
            token.getSourceMIPSProgram(),
            token.getSourceLine(),
            token.getStartPos(),
            "\"" + token.getValue() + "\": " + mess
        ));
    }
}