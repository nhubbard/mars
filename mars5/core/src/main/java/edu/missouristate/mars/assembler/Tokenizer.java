package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.InstructionDefaults;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/*
Copyright (c) 2003-2013,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

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
 * A tokenizer is capable of tokenizing a complete MIPS program, or a given line from a MIPS
 * program. Since MIPS is line-oriented, each line defines a complete statement. Tokenizing is the
 * process of analyzing the input MIPS program for the purpose of recognizing each MIPS language
 * element. The types of language elements are known as "tokens". MIPS tokens are defined in the
 * TokenTypes class.<br>
 * <br>
 * Example: <br>
 * The MIPS statement <code>here:  lw  $t3, 8($t4)   #load third member of array</code><br>
 * generates the following token list<br>
 * IDENTIFIER, COLON, OPERATOR, REGISTER_NAME, COMMA, INTEGER_5, LEFT_PAREN, REGISTER_NAME,
 * RIGHT_PAREN, COMMENT<br>
 *
 * @author Pete Sanderson
 * @version August 2003
 */
public class Tokenizer {

  private final InstructionSet instructions;
  private ErrorList errors;
  private MIPSprogram sourceMIPSprogram;
  private final Map<String, String> equivalents = new HashMap<>();
  // Single-character escape spellings and their corresponding values.
  private static final String escapedCharacters = "'\"\\ntbrf0";
  private static final int[] escapedCharactersValues = {'\'', '"', '\\', '\n', '\t', '\b', '\r', '\f', 0};

  /** Simple constructor. Initializes empty error list. */
  public Tokenizer() {
    this(null);
  }

  /**
   * Constructor for use with existing MIPSprogram. Designed to be used with Macro feature.
   *
   * @param program A previously-existing MIPSprogram object or null if none.
   */
  public Tokenizer(MIPSprogram program) {
    this(program, null);
  }

  /** Tokenizes against a session instruction set; null uses the default instruction catalog. */
  public Tokenizer(MIPSprogram program, InstructionSet instructions) {
    this.instructions = instructions;
    errors = program == null ? new ErrorList() : program.newErrorList();
    sourceMIPSprogram = program;
  }

  /**
   * Will tokenize a complete MIPS program. MIPS is line oriented (not free format), so we will be
   * line-oriented too.
   *
   * @param p The MIPSprogram to be tokenized.
   * @return An ArrayList representing the tokenized program. Each list member is a TokenList that
   *     represents a tokenized source statement from the MIPS program.
   */
  public ArrayList<TokenList> tokenize(MIPSprogram p) throws ProcessingException {
    return tokenize(p, () -> {});
  }

  public ArrayList<TokenList> tokenize(MIPSprogram p, Runnable checkCancellation)
      throws ProcessingException {
    sourceMIPSprogram = p;
    equivalents.clear();
    ArrayList<TokenList> tokenList = new ArrayList<>();
    ArrayList<SourceLine> source = processIncludes(p, checkCancellation);
    p.setSourceLineList(source);
    TokenList currentLineTokens;
    String sourceLine;
    for (int i = 0; i < source.size(); i++) {
      sourceLine = source.get(i).getSource();
      currentLineTokens = tokenizeLine(p, i + 1, sourceLine, true, checkCancellation);
      tokenList.add(currentLineTokens);
      // DPS 03-Jan-2013. Related to 11-July-2012. If source code substitution was made
      // based on .eqv directive during tokenizing, the processed line, a String, is
      // not the same object as the original line.  Thus I can use != instead of !equals()
      // This IF statement will replace original source with source modified by .eqv substitution.
      // Not needed by assembler, but looks better in the Text Segment Display.
      if (!sourceLine.isEmpty() && !sourceLine.equals(currentLineTokens.getProcessedLine())) {
        source.set(
            i,
            new SourceLine(
                currentLineTokens.getProcessedLine(),
                source.get(i).getMIPSprogram(),
                source.get(i).getLineNumber()));
      }
    }
    if (errors.errorsOccurred()) {
      throw new ProcessingException(errors);
    }
    return tokenList;
  }

  private record IncludeFrame(MIPSprogram program, ListIterator<String> lines) {
    IncludeFrame(MIPSprogram program) {
      this(program, program.getSourceList().listIterator());
    }
  }

  // Expand includes depth first, preserving their original file and line for diagnostics.
  private ArrayList<SourceLine> processIncludes(
      MIPSprogram root, Runnable checkCancellation)
      throws ProcessingException {
    var pending = new ArrayDeque<IncludeFrame>();
    pending.push(new IncludeFrame(root));
    var includedPaths = new HashSet<Path>();
    var result = new ArrayList<SourceLine>(root.getSourceList().size());
    while (!pending.isEmpty()) {
      var frame = pending.peek();
      if (!frame.lines().hasNext()) {
        pending.pop();
        continue;
      }
      checkCancellation.run();
      MIPSprogram program = frame.program();
      String line = frame.lines().next();
      int lineNumber = frame.lines().nextIndex();
      TokenList candidates = scanPossibleInclude(program, lineNumber, line, checkCancellation);
      Token include = firstIncludePath(candidates);
      String filename = quotedIncludePath(include);
      if (filename == null) {
        if (include != null) {
          errors.add(
              new ErrorMessage(
                  program,
                  include.getSourceLine(),
                  include.getStartPos(),
                  "Unterminated .include path"));
        }
        result.add(new SourceLine(line, program, lineNumber));
        continue;
      }
      // This line is replaced by the include, so the assembly pass will not check its tokens.
      reportLexicalErrors(program, lineNumber, line, candidates);
      // Handle either absolute or relative pathname for .include file.
      if (!new File(filename).isAbsolute()) {
        File parent = new File(program.getFilename()).getAbsoluteFile().getParentFile();
        filename = new File(parent, filename).getPath();
      }
      MIPSprogram incl = program.createRelatedProgram();
      try {
        incl.readIncludedSource(filename, checkCancellation);
      } catch (ProcessingException p) {
        errors.add(
            new ErrorMessage(
                program,
                include.getSourceLine(),
                include.getStartPos(),
                "Error reading include file " + filename));
        throw new ProcessingException(errors);
      }
      if (includedPaths.isEmpty()) {
        includedPaths.add(root.getIncludeIdentity());
      }
      if (!includedPaths.add(incl.getIncludeIdentity())) {
        errors.add(
            new ErrorMessage(
                program,
                include.getSourceLine(),
                include.getStartPos(),
                "Recursive include of file " + incl.getFilename()));
        throw new ProcessingException(errors);
      }
      pending.push(new IncludeFrame(incl));
    }
    return result;
  }

  /**
   * Returns the first include path in one physical line, before any equivalence expansion, or
   * null when the include prepass would leave the line in place. The path's spelling is literal.
   */
  public String findIncludePath(String sourceLine) {
    IncludePath include = findInclude(sourceLine);
    return include == null ? null : include.path();
  }

  /** Finds the include and the zero-based offset of its opening quote in a physical line. */
  public IncludePath findInclude(String sourceLine) {
    var candidates = scanPossibleInclude(sourceMIPSprogram, 1, sourceLine, () -> {});
    var token = firstIncludePath(candidates);
    String path = quotedIncludePath(token);
    return path == null ? null : new IncludePath(path, token.getStartPos() - 1);
  }

  public record IncludePath(String path, int offset) {}

  private TokenList scanPossibleInclude(
      MIPSprogram program, int lineNumber, String line, Runnable checkCancellation) {
    return line.indexOf('.') < 0 ? null : scanLine(program, lineNumber, line, checkCancellation);
  }

  private static Token firstIncludePath(TokenList tokens) {
    if (tokens == null) {
      return null;
    }
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i - 1).getValue().equalsIgnoreCase(Directives.INCLUDE.getName())
          && tokens.get(i).getType() == TokenTypes.QUOTED_STRING) {
        return tokens.get(i);
      }
    }
    return null;
  }

  private static String quotedIncludePath(Token path) {
    if (path == null || !path.hasClosingDoubleQuote()) {
      return null;
    }
    String value = path.getValue();
    return value.substring(1, value.length() - 1);
  }

  /**
   * Used only to create a token list for the example provided with each instruction specification.
   *
   * @param example The example MIPS instruction to be tokenized.
   * @return An TokenList representing the tokenized instruction. Each list member is a Token that
   *     represents one language element.
   * @throws ProcessingException This occurs only if the instruction specification itself contains
   *     one or more lexical (i.e. token) errors.
   */
  public TokenList tokenizeExampleInstruction(String example) throws ProcessingException {
    TokenList result;
    result = tokenizeLine(sourceMIPSprogram, 0, example, false);
    if (errors.errorsOccurred()) {
      throw new ProcessingException(errors);
    }
    return result;
  }

  /**
   * Will tokenize one line of source code. If lexical errors are discovered, they are noted in an
   * ErrorMessage object which is added to the ErrorList. Will NOT throw an exception yet because we
   * want to persevere beyond first error.
   *
   * @param lineNum line number from source code (used in error message)
   * @param theLine String containing source code
   * @return the generated token list for that line
   */
  /*
   *
   * Tokenizing is not as easy as it appears at first blush, because the typical
   * delimiters: space, tab, comma, can all appear inside MIPS quoted ASCII strings!
   * Also, spaces are not as necessary as they seem, the following line is accepted
   * and parsed correctly by SPIM:    label:lw,$t4,simple#comment
   * as is this weird variation:      label  :lw  $t4  ,simple ,  ,  , # comment
   *
   * as is this line:  stuff:.asciiz"# ,\n\"","aaaaa"  (interestingly, if you put
   * additional characters after the \", they are ignored!!)
   *
   * I also would like to know the starting character position in the line of each
   * token, for error reporting purposes.  StringTokenizer cannot give you this.
   *
   * Given all the above, it is just as easy to "roll my own" as to use StringTokenizer
   */

  // Modified for release 4.3, to preserve existing API.
  public TokenList tokenizeLine(int lineNum, String theLine) {
    return tokenizeLine(sourceMIPSprogram, lineNum, theLine, true);
  }

  /**
   * Will tokenize one line of source code. If lexical errors are discovered, they are noted in an
   * ErrorMessage object which is added to the provided ErrorList instead of the Tokenizer's error
   * list. Will NOT throw an exception.
   *
   * @param lineNum line number from source code (used in error message)
   * @param theLine String containing source code
   * @param callerErrorList errors will go into this list instead of tokenizer's list.
   * @return the generated token list for that line
   */
  public TokenList tokenizeLine(int lineNum, String theLine, ErrorList callerErrorList) {
    return tokenizeLine(lineNum, theLine, callerErrorList, true);
  }

  /**
   * Will tokenize one line of source code. If lexical errors are discovered, they are noted in an
   * ErrorMessage object which is added to the provided ErrorList instead of the Tokenizer's error
   * list. Will NOT throw an exception.
   *
   * @param lineNum line number from source code (used in error message)
   * @param theLine String containing source code
   * @param callerErrorList errors will go into this list instead of tokenizer's list.
   * @param doEqvSubstitutes boolean param set true to perform .eqv substitutions, else false
   * @return the generated token list for that line
   */
  public TokenList tokenizeLine(
      int lineNum, String theLine, ErrorList callerErrorList, boolean doEqvSubstitutes) {
    return tokenizeLine(lineNum, theLine, callerErrorList, doEqvSubstitutes, () -> {}, null);
  }

  private TokenList tokenizeLine(
      int lineNum, String theLine, ErrorList callerErrorList, boolean doEqvSubstitutes,
      Runnable checkCancellation, Macro.ExpandedLine expansion) {
    ErrorList saveList = this.errors;
    this.errors = callerErrorList;
    try {
      return tokenizeLine(sourceMIPSprogram, lineNum, theLine, doEqvSubstitutes, checkCancellation,
          expansion);
    } finally {
      this.errors = saveList;
    }
  }

  TokenList tokenizeMacroLine(
      int lineNum, Macro.ExpandedLine expansion, ErrorList callerErrorList,
      Runnable checkCancellation) {
    var tokens = tokenizeLine(lineNum, expansion.text(), callerErrorList, true, checkCancellation,
        expansion);
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      token.setDiagnosticOrigin(expansion.originAt(token.getOriginalStartPos()));
    }
    return tokens;
  }

  /** Reads a physical .eqv definition, or returns null if it does not validly define the name. */
  public static String readEquivalenceReplacement(String line, String name) {
    var tokenizer = new Tokenizer();
    tokenizer.tokenizeLine(1, line);
    return tokenizer.errors.errorsOccurred() ? null : tokenizer.equivalents.get(name);
  }

  /** Tokenizes a detached line. The replacement lookup returns null for an undefined name. */
  public static TokenList tokenizeWithEquivalences(
      String line,
      ErrorList errors,
      Function<String, String> replacements,
      Runnable checkCancellation) {
    var tokenizer = new Tokenizer();
    return tokenizer.expandDetachedLine(line, errors, replacements, checkCancellation, null);
  }

  /** Tokenizes a detached line and preserves the source positions of expanded tokens. */
  public static MappedLine tokenizeMappedLine(
      String line,
      ErrorList errors,
      Function<String, String> replacements,
      Runnable checkCancellation) {
    var edits = new ArrayList<SourceEdit>();
    return new MappedLine(expandLine(line, errors, replacements, checkCancellation, edits), edits);
  }

  public static final class MappedLine {
    private final ExpandedLine expanded;
    private final List<SourceEdit> edits;

    private MappedLine(ExpandedLine expanded, List<SourceEdit> edits) {
      this.expanded = expanded;
      this.edits = edits;
    }

    public TokenList tokens() {
      return expanded.tokens();
    }

    public ExpandedLine expanded() {
      return expanded;
    }

    /** Returns zero-based written offsets at which an equivalence was substituted. */
    public Set<Integer> substitutionOffsets() {
      var offsets = new HashSet<Integer>();
      for (SourceEdit edit : edits) {
        offsets.add(edit.originalStart());
      }
      return Set.copyOf(offsets);
    }

    /** Returns the written offset of the directive that introduced the definition, or -1. */
    public int equivalenceDirectiveOffset() {
      if (expanded.definition() == null) {
        return -1;
      }
      return tokens().get(equivalenceDirectiveIndex(tokens())).getOriginalStartPos() - 1;
    }

    /** Maps a zero-based token offset to the original character or the alias that supplied it. */
    public int originalOffset(int offset) {
      return Tokenizer.originalOffset(edits, offset);
    }
  }

  private static int originalOffset(List<SourceEdit> edits, int offset) {
    for (int i = edits.size() - 1; i >= 0; i--) {
      SourceEdit edit = edits.get(i);
      if (offset >= edit.start() + edit.insertedLength()) {
        offset += edit.removedLength() - edit.insertedLength();
      } else if (offset >= edit.start()) {
        offset = edit.start();
      }
    }
    return offset;
  }

  private record SourceEdit(int start, int removedLength, int insertedLength, int originalStart) {}

  /** An equivalence recorded while processing a detached line. */
  public record EquivalenceDefinition(String name, String replacement) {}

  /** The expanded tokens and the optional definition introduced by that same expansion. */
  public record ExpandedLine(TokenList tokens, EquivalenceDefinition definition) {}

  public static ExpandedLine expandLine(
      String line,
      ErrorList errors,
      Function<String, String> replacements,
      Runnable checkCancellation) {
    return expandLine(line, errors, replacements, checkCancellation, null);
  }

  private static ExpandedLine expandLine(
      String line,
      ErrorList errors,
      Function<String, String> replacements,
      Runnable checkCancellation,
      List<SourceEdit> edits) {
    var tokenizer = new Tokenizer();
    TokenList tokens =
        tokenizer.expandDetachedLine(line, errors, replacements, checkCancellation, edits);
    var definitions = tokenizer.equivalents.entrySet().iterator();
    if (definitions.hasNext() && !errors.errorsOccurred()) {
      var definition = definitions.next();
      return new ExpandedLine(
          tokens, new EquivalenceDefinition(definition.getKey(), definition.getValue()));
    }
    return new ExpandedLine(tokens, null);
  }

  private TokenList expandDetachedLine(
      String line,
      ErrorList errors,
      Function<String, String> replacements,
      Runnable checkCancellation,
      List<SourceEdit> edits) {
    this.errors = errors;
    TokenList tokens = tokenizeLine(null, 1, line, false, checkCancellation);
    return processEqv(null, 1, line, tokens, replacements, checkCancellation, edits, null);
  }

  /**
   * Will tokenize one line of source code. If lexical errors are discovered, they are noted in an
   * ErrorMessage object which is added to the provided ErrorList instead of the Tokenizer's error
   * list. Will NOT throw an exception.
   *
   * @param program MIPSprogram containing this line of source
   * @param lineNum line number from source code (used in error message)
   * @param theLine String containing source code
   * @param doEqvSubstitutes boolean param set true to perform .eqv substitutions, else false
   * @return the generated token list for that line
   */
  public TokenList tokenizeLine(
      MIPSprogram program, int lineNum, String theLine, boolean doEqvSubstitutes) {
    return tokenizeLine(program, lineNum, theLine, doEqvSubstitutes, () -> {});
  }

  private TokenList tokenizeLine(
      MIPSprogram program, int lineNum, String theLine, boolean doEqvSubstitutes,
      Runnable checkCancellation) {
    return tokenizeLine(program, lineNum, theLine, doEqvSubstitutes, checkCancellation, null);
  }

  private TokenList tokenizeLine(
      MIPSprogram program, int lineNum, String theLine, boolean doEqvSubstitutes,
      Runnable checkCancellation, Macro.ExpandedLine expansion) {
    TokenList result = scanLine(program, lineNum, theLine, checkCancellation);
    reportLexicalErrors(program, lineNum, theLine, result, null, expansion);
    if (doEqvSubstitutes && !theLine.isEmpty()) {
      return processEqv(
          program, lineNum, theLine, result, equivalents::get, checkCancellation, null, expansion);
    }
    return result;
  }

  private TokenList scanLine(
      MIPSprogram program, int lineNum, String theLine, Runnable checkCancellation) {
    checkCancellation.run();
    TokenList result = new TokenList();
    if (theLine.isEmpty()) {
      return result;
    }
    // will be faster to work with char arrays instead of strings
    char c;
    char[] line = theLine.toCharArray();
    int linePos = 0;
    char[] token = new char[line.length];
    int tokenPos = 0;
    int tokenStartPos = 1;
    boolean insideQuotedString = false;
    int nextCancellationCheck = 256;
    if (sourceMIPSprogram != null && sourceMIPSprogram.assemblyContext().debug()) {
      System.out.println("source line --->" + theLine + "<---");
    }
    // Each iteration of this loop processes one character in the source line.
    while (linePos < line.length) {
      if (linePos >= nextCancellationCheck) {
        checkCancellation.run();
        nextCancellationCheck = linePos + 256;
      }
      c = line[linePos];
      if (insideQuotedString) { // everything goes into token
        token[tokenPos++] = c;
        if (c == '"'
            && token[tokenPos - 2] != '\\') { // If quote not preceded by backslash, this is end
          this.processCandidateToken(
              token, program, lineNum, tokenPos, tokenStartPos, result);
          tokenPos = 0;
          insideQuotedString = false;
        }
      } else { // not inside a quoted string, so be sensitive to delimiters
        switch (c) {
          case '#': // # denotes comment that takes remainder of line
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
            }
            tokenStartPos = linePos + 1;
            tokenPos = line.length - linePos;
            System.arraycopy(line, linePos, token, 0, tokenPos);
            this.processCandidateToken(
                token, program, lineNum, tokenPos, tokenStartPos, result);
            linePos = line.length;
            tokenPos = 0;
            break;
          case ' ':
          case '\t':
          case ',': // space, tab or comma is delimiter
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            break;
          // These two guys are special.  Will be recognized as unary if and only if two conditions
          // hold:
          // 1. Immediately followed by a digit (will use look-ahead for this).
          // 2. Previous token, if any, is _not_ an IDENTIFIER
          // Otherwise considered binary and thus a separate token.  This is a slight hack but
          // reasonable.
          case '+':
          case '-':
            // Here's the REAL hack: recognizing signed exponent in E-notation floating point!
            // (e.g. 1.2e-5) Add the + or - to the token and keep going.  DPS 17 Aug 2005
            if (tokenPos > 0
                && line.length >= linePos + 2
                && Character.isDigit(line[linePos + 1])
                && (line[linePos - 1] == 'e' || line[linePos - 1] == 'E')) {
              token[tokenPos++] = c;
              break;
            }
            // End of REAL hack.
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            if (!((result.isEmpty()
                    || result.get(result.size() - 1).getType() != TokenTypes.IDENTIFIER)
                && (line.length >= linePos + 2 && Character.isDigit(line[linePos + 1])))) {
              // treat it as binary.....
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            break;
          // these are other single-character tokens
          case ':':
          case '(':
          case ')':
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            this.processCandidateToken(
                token, program, lineNum, tokenPos, tokenStartPos, result);
            tokenPos = 0;
            break;
          case '"': // we're not inside a quoted string, so start a new token...
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            insideQuotedString = true;
            break;
          case '\'': // start of character constant (single quote).
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            // Our strategy is to process the whole thing right now...
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c; // Put the quote in token[0]
            int lookaheadChars = line.length - linePos - 1;
            // need minimum 2 more characters, 1 for char and 1 for ending quote
            if (lookaheadChars < 2) {
              break; // gonna be an error
            }
            c = line[++linePos];
            token[tokenPos++] = c; // grab second character, put it in token[1]
            if (c == '\'') {
              break; // gonna be an error: nothing between the quotes
            }
            c = line[++linePos];
            token[tokenPos++] = c; // grab third character, put it in token[2]
            // Process if we've either reached second, non-escaped, quote or end of line.
            if (c == '\'' && token[1] != '\\' || lookaheadChars == 2) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
              tokenStartPos = linePos + 1;
              break;
            }
            // At this point, there is at least one more character on this line. If we're
            // still here after seeing a second quote, it was escaped.  Not done yet;
            // we either have an escape code, an octal code (also escaped) or invalid.
            c = line[++linePos];
            token[tokenPos++] = c; // grab fourth character, put it in token[3]
            // Process, if this is ending quote for escaped character or if at end of line
            if (c == '\'' || lookaheadChars == 3) {
              this.processCandidateToken(
                  token, program, lineNum, tokenPos, tokenStartPos, result);
              tokenPos = 0;
              tokenStartPos = linePos + 1;
              break;
            }
            // At this point, we've handled all legal possibilities except octal, e.g. '\377'
            // Proceed, if enough characters remain to finish off octal.
            if (lookaheadChars >= 5) {
              c = line[++linePos];
              token[tokenPos++] = c; // grab fifth character, put it in token[4]
              if (c != '\'') {
                // still haven't reached end, last chance for validity!
                c = line[++linePos];
                token[tokenPos++] = c; // grab sixth character, put it in token[5]
              }
            }
            // process no matter what...we either have a valid character by now or not
            this.processCandidateToken(
                token, program, lineNum, tokenPos, tokenStartPos, result);
            tokenPos = 0;
            tokenStartPos = linePos + 1;
            break;
          default:
            if (tokenPos == 0) {
              tokenStartPos = linePos + 1;
            }
            token[tokenPos++] = c;
            break;
        } // switch
      } // if (insideQuotedString)
      linePos++;
    } // while
    if (tokenPos > 0) {
      this.processCandidateToken(token, program, lineNum, tokenPos, tokenStartPos, result);
    }
    return result;
  }

  // A replacement is source text. Re-tokenize it so that it can supply punctuation or a
  // directive, including a new .eqv definition.
  private TokenList processEqv(
      MIPSprogram program, int lineNum, String theLine, TokenList tokens,
      Function<String, String> replacements, Runnable checkCancellation, List<SourceEdit> edits,
      Macro.ExpandedLine expansion) {
    EquivalenceUse use = nextEquivalenceUse(
        program, lineNum, theLine, tokens, replacements, List.of(), expansion);
    if (use == null) {
      return tokens;
    }
    MIPSprogram originalProgram = program;
    String originalLine = theLine;
    TokenList originalTokens = tokens;
    if (edits == null) {
      edits = new ArrayList<>();
    }
    int originalColumn = 0;
    var path = new ArrayDeque<EquivalenceExpansion>();
    var active = new HashSet<String>();
    do {
      Token token = use.token();
      String symbol = token.getValue();
      int start = token.getStartPos() - 1;
      // Substitutions are selected left to right. While expanding a replacement, the text
      // after it stays fixed, so the suffix length tracks its end even as the line grows.
      int remaining = theLine.length() - start;
      while (!path.isEmpty() && remaining <= path.getLast().suffixLength()) {
        active.remove(path.removeLast().symbol());
      }
      if (path.isEmpty()) {
        originalColumn = token.getStartPos() + originalLine.length() - theLine.length();
      }
      if (!active.add(symbol)) {
        var chain = new StringJoiner(" -> ");
        path.forEach(step -> chain.add(step.symbol()));
        addLineError(program, lineNum, originalColumn,
            "Circular .eqv expansion " + chain.add(symbol), expansion);
        tokens.setProcessedLine(originalLine);
        break;
      }
      int end = token.getEndPos() - 1;
      path.addLast(new EquivalenceExpansion(symbol, theLine.length() - end));
      edits.add(new SourceEdit(start, end - start, use.replacement().length(), originalColumn - 1));
      theLine = theLine.substring(0, start) + use.replacement() + theLine.substring(end);
      program = sourceMIPSprogram;
      tokens = scanLine(program, lineNum, theLine, checkCancellation);
      use = nextEquivalenceUse(program, lineNum, theLine, tokens, replacements, edits, expansion);
    } while (use != null);
    for (int i = 0; i < tokens.size(); i++) {
      checkCancellation.run();
      Token token = tokens.get(i);
      token.setOriginalStartPos(originalColumn(token, edits));
    }
    reportLexicalErrors(originalProgram, lineNum, originalLine, tokens, originalTokens, expansion);
    return tokens;
  }

  private record EquivalenceExpansion(String symbol, int suffixLength) {}

  private record EquivalenceUse(Token token, String replacement) {}

  private EquivalenceUse nextEquivalenceUse(
      MIPSprogram program,
      int lineNum,
      String line,
      TokenList tokens,
      Function<String, String> replacements,
      List<SourceEdit> edits,
      Macro.ExpandedLine expansion) {
    tokens.setProcessedLine(line);
    if (recordEquivalence(program, lineNum, line, tokens, edits, expansion)) {
      return null;
    }
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.getType() == TokenTypes.IDENTIFIER) {
        String replacement = replacements.apply(token.getValue());
        if (replacement != null) {
          return new EquivalenceUse(token, replacement);
        }
      }
    }
    return null;
  }

  private static int originalColumn(Token token, List<SourceEdit> edits) {
    return originalOffset(edits, token.getStartPos() - 1) + 1;
  }

  private static int equivalenceDirectiveIndex(TokenList tokens) {
    // MARS also recognizes .eqv as the third token, even without a leading label.
    return tokens.get(0).getType() == TokenTypes.DIRECTIVE ? 0 : 2;
  }

  private boolean recordEquivalence(
      MIPSprogram program, int lineNum, String line, TokenList tokens, List<SourceEdit> edits,
      Macro.ExpandedLine expansion) {
    if (tokens.size() < 3) {
      return false;
    }
    int directive = equivalenceDirectiveIndex(tokens);
    Token directiveToken = tokens.get(directive);
    if (directiveToken.getType() != TokenTypes.DIRECTIVE
        || Directives.matchDirective(directiveToken.getValue()) != Directives.EQV) {
      return false;
    }
    int last = tokens.size() - (tokens.get(tokens.size() - 1).getType() == TokenTypes.COMMENT ? 2 : 1);
    if (last < directive + 2) {
      addLineError(program, lineNum, originalColumn(directiveToken, edits),
          "Too few operands for .eqv directive", expansion);
      return true;
    }
    Token name = tokens.get(directive + 1);
    if (name.getType() != TokenTypes.IDENTIFIER) {
      addLineError(program, lineNum, originalColumn(directiveToken, edits),
          "Malformed .eqv directive", expansion);
      return true;
    }
    String symbol = name.getValue();
    for (int i = directive + 2; i <= last; i++) {
      if (tokens.get(i).getValue().equals(symbol)) {
        addLineError(program, lineNum, originalColumn(directiveToken, edits),
            "Cannot substitute " + symbol + " for itself in .eqv directive", expansion);
        return true;
      }
    }
    int start = tokens.get(directive + 2).getStartPos() - 1;
    int end = tokens.get(last).getEndPos() - 1;
    String expression = line.substring(start, end);
    String previous = equivalents.putIfAbsent(symbol, expression);
    if (previous != null && !previous.equals(expression)) {
      addLineError(program, lineNum, originalColumn(name, edits),
          "\"" + symbol + "\" is already defined", expansion);
    }
    return true;
  }

  /**
   * Fetch this Tokenizer's error list.
   *
   * @return the error list
   */
  public ErrorList getErrors() {
    return errors;
  }

  // Given candidate token and its position, will classify and record it.
  private void processCandidateToken(
      char[] token,
      MIPSprogram program,
      int line,
      int tokenPos,
      int tokenStartPos,
      TokenList tokenList) {
    String value = new String(token, 0, tokenPos);
    if (value.charAt(0) == '\'') {
      int character = decodeCharacterLiteral(value);
      if (character >= 0) {
        value = Integer.toString(character);
      }
    }
    TokenTypes type =
        TokenTypes.matchTokenType(
            value, instructions == null ? InstructionDefaults.get() : instructions);
    Token toke = new Token(type, value, program, line, tokenStartPos, tokenPos);
    tokenList.add(toke);
  }

  private void reportLexicalErrors(
      MIPSprogram program, int line, String source, TokenList tokens) {
    reportLexicalErrors(program, line, source, tokens, null, null);
  }

  private void reportLexicalErrors(
      MIPSprogram program, int line, String source, TokenList tokens, TokenList originalTokens,
      Macro.ExpandedLine expansion) {
    int originalIndex = 0;
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.getType() == TokenTypes.ERROR) {
        int column = token.getOriginalStartPos();
        if (originalTokens != null) {
          // Mapped offsets stay in source order. Skip errors already reported on the written line.
          while (originalIndex < originalTokens.size()
              && originalTokens.get(originalIndex).getStartPos() < column) {
            originalIndex++;
          }
          if (originalIndex < originalTokens.size()) {
            Token original = originalTokens.get(originalIndex);
            if (original.getStartPos() == column && original.getType() == TokenTypes.ERROR
                && original.getValue().equals(token.getValue())) {
              continue;
            }
          }
        }
        addLineError(program, line, column,
            source + "\nInvalid language element: " + token.getValue(), expansion);
        if (errors.errorLimitExceeded()) {
          return;
        }
      }
    }
  }

  private void addLineError(
      MIPSprogram program, int line, int column, String message, Macro.ExpandedLine expansion) {
    Token origin = expansion == null ? null : expansion.originAt(column);
    errors.add(origin == null
        ? new ErrorMessage(program, line, column, message)
        : new ErrorMessage(origin, message));
  }

  /** Decodes one MARS character literal, or returns -1 for an invalid spelling. */
  public static int decodeCharacterLiteral(CharSequence literal) {
    int length = literal.length();
    if (length < 3 || literal.charAt(0) != '\'' || literal.charAt(length - 1) != '\'') {
      return -1;
    }
    char character = literal.charAt(1);
    if (character != '\\') {
      return length == 3 && character != '\'' ? character : -1;
    }
    if (length == 4) {
      int index = escapedCharacters.indexOf(literal.charAt(2));
      return index >= 0 ? escapedCharactersValues[index] : -1;
    }
    if (length == 6) {
      try {
        int number = Integer.parseInt(literal, 2, length - 1, 8);
        if (number >= 0 && number <= 255) {
          return number;
        }
      } catch (NumberFormatException ignored) {
      } // if not valid octal, will fall through and reject
    }
    return -1;
  }
}
