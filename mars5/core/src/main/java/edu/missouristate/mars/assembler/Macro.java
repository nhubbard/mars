package edu.missouristate.mars.assembler;

import edu.missouristate.mars.ErrorList;
import edu.missouristate.mars.ErrorMessage;
import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/*
Copyright (c) 2013-2014.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Stores information of a macro definition.
 *
 * @author M.H.Sekhavat {@literal <sekhavat17@gmail.com>}
 */
public class Macro {
  private String name;
  private MIPSprogram program;
  private final ArrayList<String> labels;

  /**
   * first and last line number of macro definition. first line starts with .macro directive and
   * last line is .end_macro directive.
   */
  private int fromLine, toLine;

  private int origFromLine, origToLine;
  int nameColumn;

  /** arguments like <code>%arg</code> will be substituted by macro expansion */
  private ArrayList<String> args;

  public Macro() {
    name = "";
    program = null;
    fromLine = toLine = 0;
    origFromLine = origToLine = 0;
    args = new ArrayList<>();
    labels = new ArrayList<>();
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public MIPSprogram getProgram() {
    return program;
  }

  public void setProgram(MIPSprogram program) {
    this.program = program;
  }

  public int getFromLine() {
    return fromLine;
  }

  public int getOriginalFromLine() {
    return this.origFromLine;
  }

  public void setFromLine(int fromLine) {
    this.fromLine = fromLine;
  }

  public void setOriginalFromLine(int origFromLine) {
    this.origFromLine = origFromLine;
  }

  public int getToLine() {
    return toLine;
  }

  public int getOriginalToLine() {
    return this.origToLine;
  }

  public void setToLine(int toLine) {
    this.toLine = toLine;
  }

  public void setOriginalToLine(int origToLine) {
    this.origToLine = origToLine;
  }

  public ArrayList<String> getArgs() {
    return args;
  }

  SourceToken definitionSite() {
    var header = program.getSourceLineList().get(getFromLine() - 1);
    return new SourceToken(Parser.origin(header, null).location(), nameColumn);
  }

  public void setArgs(ArrayList<String> args) {
    this.args = args;
  }

  /**
   * @param obj {@link Macro} object to check if their name and count of arguments are same
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Macro macro) {
      return macro.getName().equals(name) && (macro.args.size() == args.size());
    }
    return super.equals(obj);
  }

  public void addArg(String value) {
    args.add(value);
  }

  /**
   * Substitutes macro arguments in a line of source code inside macro definition to be parsed after
   * macro expansion. <br>
   * Also appends "_M#" to all labels defined inside macro body where # is value of <code>counter
   * </code>
   *
   * @param line source line number in macro definition to be substituted
   * @param counter unique macro expansion id
   * @return <code>line</code>-th line of source code, with substituted arguments
   */
  public String getSubstitutedLine(int line, TokenList args, long counter, ErrorList errors) {
    return expandLine(line, args, counter, Set.of(), errors).text();
  }

  record TokenOrigin(Token written, Token diagnostic) {}

  record ExpandedLine(
      String text, Set<String> renamedLabels, NavigableMap<Integer, TokenOrigin> origins) {
    TokenOrigin tokenOriginAt(int column) {
      var entry = origins.ceilingEntry(text.length() - column + 1);
      return entry == null ? null : entry.getValue();
    }

    TokenOrigin tokenOriginStartingAt(int column) {
      return origins.get(text.length() - column + 1);
    }

    Token originAt(int column) {
      var origin = tokenOriginAt(column);
      return origin == null ? null : origin.diagnostic();
    }
  }

  ExpandedLine expandLine(
      int line, TokenList args, long counter, Set<String> privateArguments, ErrorList errors) {
    TokenList tokens = program.getTokenList().get(line - 1);
    var source = new StringBuilder(tokens.getProcessedLine());
    HashSet<String> renamedLabels = null;
    var origins = new TreeMap<Integer, TokenOrigin>();

    // Replace from right to left so earlier offsets stay valid. Record token starts
    // as distances from the end because the remaining replacements are all to the left.
    for (int i = tokens.size() - 1; i >= 0; i--) {
      Token token = tokens.get(i);
      Token origin = token;
      int start = token.getStartPos() - 1;
      String substitute;
      boolean renamed;
      if (tokenIsMacroParameter(token.getValue(), true)) {
        int repl = -1;
        for (int j = 0; j < this.args.size(); j++) {
          if (this.args.get(j).equals(token.getValue())) {
            repl = j;
            break;
          }
        }
        substitute = token.getValue();
        if (repl != -1) {
          origin = args.get(repl + 1);
          substitute = origin.toString();
        } else {
          errors.add(new ErrorMessage(token, "Unknown macro parameter"));
        }
        renamed = repl != -1 && privateArguments.contains(substitute);
      } else if (tokenIsMacroLabel(token.getValue())) {
        substitute = token.getValue() + "_M" + counter;
        renamed = true;
      } else {
        origins.put(source.length() - start, new TokenOrigin(token, origin));
        continue;
      }
      if (renamed) {
        if (renamedLabels == null) {
          renamedLabels = new HashSet<>();
        }
        renamedLabels.add(substitute);
      }
      source.replace(start, token.getEndPos() - 1, substitute);
      origins.put(source.length() - start, new TokenOrigin(token, origin));
    }
    return new ExpandedLine(source.toString(), renamedLabels == null ? Set.of() : renamedLabels, origins);
  }

  /** returns true if <code>value</code> is name of a label defined in this macro's body. */
  private boolean tokenIsMacroLabel(String value) {
    return (Collections.binarySearch(labels, value) >= 0);
  }

  /**
   * returns whether <code>tokenValue</code> is macro parameter or not
   *
   * @param acceptSpimStyleParameters accepts SPIM-style parameters which begin with '$' if true
   */
  public static boolean tokenIsMacroParameter(
      String tokenValue, boolean acceptSpimStyleParameters) {
    if (acceptSpimStyleParameters) {
      // Bug fix: SPIM accepts parameter names that start with $ instead of %.  This can
      // lead to problems since register names also start with $.  This IF condition
      // should filter out register names.  Originally filtered those from regular set but not
      // from Coprocessor0 or Coprocessor1 register sets.  Expanded the condition.
      // DPS  7-July-2014.
      if (!tokenValue.isEmpty()
          && tokenValue.charAt(0) == '$'
          && RegisterNames.integer(tokenValue) == null
          && RegisterNames.coprocessor0(tokenValue) == null
          && // added 7-July-2014
          RegisterNames.floating(tokenValue) == null) // added 7-July-2014
      {
        return true;
      }
    }
    return tokenValue.length() > 1 && tokenValue.charAt(0) == '%';
  }

  /** Whether a comment-free invocation has the optional outer SPIM-style parentheses. */
  public static boolean hasCallParentheses(TokenList tokens) {
    return hasCallParentheses(tokens, 0);
  }

  /** The same check with the operator at an offset in a comment-free token list. */
  public static boolean hasCallParentheses(TokenList tokens, int operationIndex) {
    return tokens.size() - operationIndex > 2
        && tokens.get(operationIndex + 1).getType() == TokenTypes.LEFT_PAREN
        && tokens.get(tokens.size() - 1).getType() == TokenTypes.RIGHT_PAREN;
  }

  public void addLabel(String value) {
    labels.add(value);
  }

  /** Operations to be done on this macro before it is committed in macro pool. */
  public void readyForCommit() {
    Collections.sort(labels);
  }
}
