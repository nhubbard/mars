/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.venus.editors.jeditsyntax;

import edu.missouristate.mars.venus.HelpHelpAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/**
 * Handly little class to contain help information for a popupMenu or
 * tool tip item.
 */
public class PopupHelpItem {
    private final String tokenText;
    private String example;
    private String description;
    private final boolean exact;        // from exact match?
    private int exampleLength;
    private static final String spaces = "                                        "; // 40 spaces

    /**
     * Create popup help item.  This is created as result of either an exact-match or
     * prefix-match search.  Note that prefix-match search includes exact as well as partial matches.
     *
     * @param tokenText   The document text that matched
     * @param example     An example instruction
     * @param description A textual description of the instruction
     * @param exact       True if match occurred as result of exact-match search, false otherwise.
     */
    public PopupHelpItem(String tokenText, String example, @NotNull String description, boolean exact) {
        this.tokenText = tokenText;
        this.example = example;
        if (exact) {
            this.description = description;
        } else {
            int detailPosition = description.indexOf(HelpHelpAction.descriptionDetailSeparator);
            this.description = (detailPosition == -1) ? description : description.substring(0, detailPosition);
        }
        this.exampleLength = this.example.length();
        this.exact = exact;
    }

    /**
     * Create popup help item, where match is result of an exact-match search.
     *
     * @param tokenText   The document text that matched
     * @param example     An example instruction
     * @param description A textual description of the instruction
     */
    public PopupHelpItem(String tokenText, String example, @NotNull String description) {
        this(tokenText, example, description, true);

    }

    /**
     * The document text that mached this item
     */
    public String getTokenText() {
        return this.tokenText;
    }

    public String getExample() {
        return this.example;
    }

    public String getDescription() {
        return this.description;
    }

    /**
     * Determines whether match occurred in an exact-match or prefix-match search.
     * Note this can return false even if the match is exact because prefix-match also
     * includes exact match results.  E.g. prefix match on "lw" will match both "lwl" and "lw".
     *
     * @return True if exact-match search, false otherwise.
     */
    public boolean getExact() {
        return this.exact;
    }

    public int getExampleLength() {
        return this.exampleLength;
    }

    // for performance purposes, length limited to example length + 40
    public String getExamplePaddedToLength(int length) {
        String result;
        if (length > this.exampleLength) {
            int numSpaces = length - this.exampleLength;
            if (numSpaces > spaces.length()) {
                numSpaces = spaces.length();
            }
            result = this.example + spaces.substring(0, numSpaces);
        } else if (length == this.exampleLength) {
            result = this.example;
        } else {
            result = this.example.substring(0, length);
        }
        return result;
    }

    public void setExample(@NotNull String example) {
        this.example = example;
        this.exampleLength = example.length();
    }

    public void setDescription(String description) {
        this.description = description;
    }

    // Utility method.  Traverse ArrayList of PopupHelpItem objects
    // and return String length of longest example.
    public static int maxExampleLength(@Nullable ArrayList<PopupHelpItem> matches) {
        int length = 0;
        if (matches != null) {
            for (PopupHelpItem match : matches) {
                if (match != null) {
                    length = Math.max(length, match.getExampleLength());
                }
            }
        }
        return length;
    }
}
