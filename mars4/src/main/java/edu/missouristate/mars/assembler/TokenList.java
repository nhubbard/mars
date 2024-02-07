package edu.missouristate.mars.assembler;

import edu.missouristate.mars.util.ExcludeFromJacocoGeneratedReport;

import java.util.ArrayList;

/**
 * Represents the list of tokens in a single line of MIPS code.  It uses, but is not
 * a subclass of, ArrayList.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public class TokenList implements Cloneable {
    private ArrayList<Token> tokenList;
    private String processedLine;// DPS 03-Jan-2013

    /**
     * Constructor for objects of class TokenList
     */
    public TokenList() {
        tokenList = new ArrayList<>();
        processedLine = ""; // DPS 03-Jan-2013
    }

    /**
     * Use this to record the source line String for this token list
     * after possible modification (textual substitution) during
     * assembly preprocessing.  The modified source will be displayed in
     * the Text Segment Display.
     *
     * @param line The source line, possibly modified (possibly not)
     */
    public void setProcessedLine(String line) {
        processedLine = line;
    }

    /**
     * Retrieve the source line String associated with this
     * token list.  It may or may not have been modified during
     * assembly preprocessing.
     *
     * @return The source line for this token list.
     */
    public String getProcessedLine() {
        return processedLine;
    }

    /**
     * Returns requested token given position number (starting at 0).
     *
     * @param pos Position in token list.
     * @return the requested token, or ArrayIndexOutOfBounds exception
     */
    public Token get(int pos) {
        return tokenList.get(pos);
    }

    /**
     * Replaces token at position with different one.  Will throw
     * ArrayIndexOutOfBounds exception if position does not exist.
     *
     * @param pos         Position in token list.
     * @param replacement Replacement token
     */
    public void set(int pos, Token replacement) {
        tokenList.set(pos, replacement);
    }

    /**
     * Returns number of tokens in list.
     *
     * @return token count.
     */
    public int size() {
        return tokenList.size();
    }

    /**
     * Adds a Token object to the end of the list.
     *
     * @param token Token object to be added.
     */
    public void add(Token token) {
        tokenList.add(token);
    }

    /**
     * Removes Token object at specified list position. Uses ArrayList remove method.
     *
     * @param pos Position in token list.  Subsequent Tokens are shifted one position left.
     * @throws IndexOutOfBoundsException if <tt>pos</tt> is < 0 or >= <tt>size()</tt>
     */
    public void remove(int pos) {
        tokenList.remove(pos);
    }

    /**
     * Returns empty/non-empty status of list.
     *
     * @return <tt>true</tt> if list has no tokens, else <tt>false</tt>.
     */
    public boolean isEmpty() {
        return tokenList.isEmpty();
    }

    /**
     * Get a String representing the token list.
     *
     * @return String version of the token list
     * (a blank is inserted after each token).
     */
    public String toString() {
        StringBuilder stringified = new StringBuilder();
        for (Object o : tokenList) stringified.append(o.toString()).append(" ");
        return stringified.toString();
    }

    /**
     * Get a String representing the sequence of token types for this list.
     *
     * @return String version of the token types for this list
     * (a blank is inserted after each token type).
     */
    public String toTypeString() {
        StringBuilder stringified = new StringBuilder();
        for (Token o : tokenList) stringified.append(o.getType().toString()).append(" ");
        return stringified.toString();
    }

    /**
     * Makes clone (shallow copy) of this token list object.
     *
     * @return the cloned list.
     */
    @SuppressWarnings("unchecked")
    @ExcludeFromJacocoGeneratedReport
    public Object clone() {
        // Clones are a bit tricky.
        // super.clone() handles primitives (e.g., values) correctly,
        // but the ArrayList itself has to be cloned separately -- otherwise clone will have
        // alias to the original token list!!
        try {
            TokenList t = (TokenList) super.clone();
            t.tokenList = (ArrayList<Token>) tokenList.clone();
            return t;
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }
}