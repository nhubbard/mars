/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package edu.missouristate.mars.venus.editors;

import edu.missouristate.mars.venus.editors.tokenmarker.Token;

import javax.swing.text.Segment;

/**
 * A <code>KeywordMap</code> is similar to a hashtable in that it maps keys
 * to values. However, the `keys' are Swing segments. This allows lookups of
 * text substrings without the overhead of creating a new string object.
 * <p>
 * This class is used by <code>CTokenMarker</code> to map keywords to ids.
 *
 * @author Slava Pestov, Mike Dillon
 * @version $Id: KeywordMap.java,v 1.16 1999/12/13 03:40:30 sp Exp $
 */
public class KeywordMap {
    /**
     * Creates a new <code>KeywordMap</code>.
     *
     * @param ignoreCase True if keys are case insensitive
     */
    public KeywordMap(boolean ignoreCase) {
        this(ignoreCase, 52);
        this.ignoreCase = ignoreCase;
    }

    /**
     * Creates a new <code>KeywordMap</code>.
     *
     * @param ignoreCase True if the keys are case insensitive
     * @param mapLength  The number of `buckets' to create.
     *                   A value of 52 will give good performance for most maps.
     */
    public KeywordMap(boolean ignoreCase, int mapLength) {
        this.mapLength = mapLength;
        this.ignoreCase = ignoreCase;
        map = new Keyword[mapLength];
    }

    /**
     * Looks up a key.
     *
     * @param text   The text segment
     * @param offset The offset of the substring within the text segment
     * @param length The length of the substring
     */
    public Token.Type lookup(Segment text, int offset, int length) {
        if (length == 0)
            return Token.Type.NULL;
        if (text.array[offset] == '%')
            return Token.Type.MACRO_ARG;  // added 12/12 M. Sekhavat
        Keyword k = map[getSegmentMapKey(text, offset, length)];
        while (k != null) {
            if (length != k.keyword.length) {
                k = k.next;
                continue;
            }
            if (SyntaxUtilities.regionMatches(ignoreCase, text, offset,
                    k.keyword))
                return k.id;
            k = k.next;
        }
        return Token.Type.NULL;
    }

    /**
     * Adds a key-value mapping.
     *
     * @param keyword The key
     * @param id      The value
     */
    public void add(String keyword, Token.Type id) {
        int key = getStringMapKey(keyword);
        map[key] = new Keyword(keyword.toCharArray(), id, map[key]);
    }

    /**
     * Returns true if the keyword map is set to be case insensitive,
     * false otherwise.
     */
    public boolean getIgnoreCase() {
        return ignoreCase;
    }

    /**
     * Sets if the keyword map should be case insensitive.
     *
     * @param ignoreCase True if the keyword map should be case
     *                   insensitive, false otherwise
     */
    public void setIgnoreCase(boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
    }

    // protected members
    protected final int mapLength;

    protected int getStringMapKey(String s) {
        return (Character.toUpperCase(s.charAt(0)) +
                Character.toUpperCase(s.charAt(s.length() - 1)))
                % mapLength;
    }

    protected int getSegmentMapKey(Segment s, int off, int len) {
        return (Character.toUpperCase(s.array[off]) +
                Character.toUpperCase(s.array[off + len - 1]))
                % mapLength;
    }

    // private members
    static class Keyword {
        public Keyword(char[] keyword, Token.Type id, Keyword next) {
            this.keyword = keyword;
            this.id = id;
            this.next = next;
        }

        public final char[] keyword;
        public final Token.Type id;
        public final Keyword next;
    }

    private final Keyword[] map;
    private boolean ignoreCase;
}
