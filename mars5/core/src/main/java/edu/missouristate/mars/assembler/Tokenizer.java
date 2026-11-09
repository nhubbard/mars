package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.mips.instructions.InstructionDefaults;
import edu.missouristate.mars.mips.instructions.InstructionSet;
import java.io.*;
import java.util.*;

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
  // The 8 escaped characters are: single quote, double quote, backslash, newline (linefeed),
  // tab, backspace, return, form feed.  The characters and their corresponding decimal codes:
  private static final String escapedCharacters = "'\"\\ntbrf0";
  private static final String[] escapedCharactersValues = {
    "39", "34", "92", "10", "9", "8", "13", "12", "0"
  };

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
    // ArrayList source = p.getSourceList();
    ArrayList<SourceLine> source = processIncludes(p, new HashSet<>(), checkCancellation);
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

  // pre-pre-processing pass through source code to process any ".include" directives.
  // When one is encountered, the contents of the included file are inserted at that
  // point.  If no .include statements, the return value is a new array list but
  // with the same lines of source code.  Uses recursion to correctly process included
  // files that themselves have .include.  Plus it will detect and report recursive
  // includes both direct and indirect.
  // DPS 11-Jan-2013
  private ArrayList<SourceLine> processIncludes(
      MIPSprogram program, Set<String> includedPaths, Runnable checkCancellation)
      throws ProcessingException {
    ArrayList<String> source = program.getSourceList();
    ArrayList<SourceLine> result = new ArrayList<>(source.size());
    for (int i = 0; i < source.size(); i++) {
      String line = source.get(i);
      Token include =
          firstIncludePath(tokenizeLine(program, i + 1, line, false, checkCancellation));
      if (include == null) {
        result.add(new SourceLine(line, program, i + 1));
        continue;
      }
      String filename = unquote(include);
      // Handle either absolute or relative pathname for .include file.
      if (!new File(filename).isAbsolute()) {
        filename = new File(program.getFilename()).getParent() + File.separator + filename;
      }
      MIPSprogram incl = program.createRelatedProgram();
      try {
        incl.readSource(filename, checkCancellation);
      } catch (ProcessingException p) {
        errors.add(
            new ErrorMessage(
                program,
                include.getSourceLine(),
                include.getStartPos(),
                "Error reading include file " + filename));
        throw new ProcessingException(errors);
      }
      String includedPath = incl.getFilename();
      if (!includedPaths.add(includedPath)) {
        errors.add(
            new ErrorMessage(
                program,
                include.getSourceLine(),
                include.getStartPos(),
                "Recursive include of file " + includedPath));
        throw new ProcessingException(errors);
      }
      result.addAll(processIncludes(incl, includedPaths, checkCancellation));
    }
    return result;
  }

  /**
   * Returns the first include path in one physical line, before any equivalence expansion, or
   * null when the include prepass would leave the line in place. The path's spelling is literal.
   */
  public String findIncludePath(String sourceLine) {
    if (sourceLine.indexOf('.') < 0) {
      return null;
    }
    Token include = firstIncludePath(tokenizeLine(1, sourceLine, new ErrorList(), false));
    return include == null ? null : unquote(include);
  }

  private static Token firstIncludePath(TokenList tokens) {
    for (int i = 1; i < tokens.size(); i++) {
      if (tokens.get(i - 1).getValue().equalsIgnoreCase(Directives.INCLUDE.getName())
          && tokens.get(i).getType() == TokenTypes.QUOTED_STRING) {
        return tokens.get(i);
      }
    }
    return null;
  }

  private static String unquote(Token path) {
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
    return tokenizeLine(lineNum, theLine, callerErrorList, doEqvSubstitutes, () -> {});
  }

  TokenList tokenizeLine(
      int lineNum, String theLine, ErrorList callerErrorList, boolean doEqvSubstitutes,
      Runnable checkCancellation) {
    ErrorList saveList = this.errors;
    this.errors = callerErrorList;
    try {
      return tokenizeLine(sourceMIPSprogram, lineNum, theLine, doEqvSubstitutes, checkCancellation);
    } finally {
      this.errors = saveList;
    }
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
              token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
          tokenPos = 0;
          insideQuotedString = false;
        }
      } else { // not inside a quoted string, so be sensitive to delimiters
        switch (c) {
          case '#': // # denotes comment that takes remainder of line
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
            }
            tokenStartPos = linePos + 1;
            tokenPos = line.length - linePos;
            System.arraycopy(line, linePos, token, 0, tokenPos);
            this.processCandidateToken(
                token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
            linePos = line.length;
            tokenPos = 0;
            break;
          case ' ':
          case '\t':
          case ',': // space, tab or comma is delimiter
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
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
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            if (!((result.isEmpty()
                    || result.get(result.size() - 1).getType() != TokenTypes.IDENTIFIER)
                && (line.length >= linePos + 2 && Character.isDigit(line[linePos + 1])))) {
              // treat it as binary.....
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            break;
          // these are other single-character tokens
          case ':':
          case '(':
          case ')':
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            this.processCandidateToken(
                token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
            tokenPos = 0;
            break;
          case '"': // we're not inside a quoted string, so start a new token...
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
              tokenPos = 0;
            }
            tokenStartPos = linePos + 1;
            token[tokenPos++] = c;
            insideQuotedString = true;
            break;
          case '\'': // start of character constant (single quote).
            if (tokenPos > 0) {
              this.processCandidateToken(
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
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
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
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
                  token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
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
                token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
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
      this.processCandidateToken(token, program, lineNum, theLine, tokenPos, tokenStartPos, result);
    }
    if (doEqvSubstitutes) {
      result = processEqv(program, lineNum, theLine, result, checkCancellation);
    }
    return result;
  }

  // A replacement is source text. Re-tokenize it so that it can supply punctuation or a
  // directive, including a new .eqv definition.
  private TokenList processEqv(
      MIPSprogram program, int lineNum, String theLine, TokenList tokens,
      Runnable checkCancellation) {
    Token token = nextEquivalenceUse(program, lineNum, theLine, tokens);
    if (token == null) {
      return tokens;
    }
    String originalLine = theLine;
    int originalColumn = 0;
    var path = new ArrayDeque<EquivalenceExpansion>();
    var active = new HashSet<String>();
    do {
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
        path.forEach(expansion -> chain.add(expansion.symbol()));
        errors.add(new ErrorMessage(
            program, lineNum, originalColumn, "Circular .eqv expansion " + chain.add(symbol)));
        tokens.setProcessedLine(originalLine);
        return tokens;
      }
      int end = start + symbol.length();
      path.addLast(new EquivalenceExpansion(symbol, theLine.length() - end));
      theLine = theLine.substring(0, start) + equivalents.get(symbol) + theLine.substring(end);
      program = sourceMIPSprogram;
      tokens = tokenizeLine(program, lineNum, theLine, false, checkCancellation);
      token = nextEquivalenceUse(program, lineNum, theLine, tokens);
    } while (token != null);
    return tokens;
  }

  private record EquivalenceExpansion(String symbol, int suffixLength) {}

  private Token nextEquivalenceUse(MIPSprogram program, int lineNum, String line, TokenList tokens) {
    if (recordEquivalence(program, lineNum, line, tokens)) {
      return null;
    }
    tokens.setProcessedLine(line);
    for (int i = 0; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.getType() == TokenTypes.IDENTIFIER && equivalents.containsKey(token.getValue())) {
        return token;
      }
    }
    return null;
  }

  private boolean recordEquivalence(MIPSprogram program, int lineNum, String line, TokenList tokens) {
    if (tokens.size() < 3) {
      return false;
    }
    // The directive can be preceded by a label and colon.
    int directive = tokens.get(0).getType() == TokenTypes.DIRECTIVE ? 0 : 2;
    Token directiveToken = tokens.get(directive);
    if (directiveToken.getType() != TokenTypes.DIRECTIVE
        || Directives.matchDirective(directiveToken.getValue()) != Directives.EQV) {
      return false;
    }
    int last = tokens.size() - (tokens.get(tokens.size() - 1).getType() == TokenTypes.COMMENT ? 2 : 1);
    if (last < directive + 2) {
      errors.add(new ErrorMessage(
          program, lineNum, directiveToken.getStartPos(), "Too few operands for .eqv directive"));
      return true;
    }
    Token name = tokens.get(directive + 1);
    if (name.getType() != TokenTypes.IDENTIFIER) {
      errors.add(new ErrorMessage(
          program, lineNum, directiveToken.getStartPos(), "Malformed .eqv directive"));
      return true;
    }
    String symbol = name.getValue();
    for (int i = directive + 2; i <= last; i++) {
      if (tokens.get(i).getValue().equals(symbol)) {
        errors.add(new ErrorMessage(program, lineNum, directiveToken.getStartPos(),
            "Cannot substitute " + symbol + " for itself in .eqv directive"));
        return true;
      }
    }
    int start = tokens.get(directive + 2).getStartPos() - 1;
    Token lastOperand = tokens.get(last);
    int end = lastOperand.getStartPos() - 1 + lastOperand.getValue().length();
    String expression = line.substring(start, end);
    String previous = equivalents.putIfAbsent(symbol, expression);
    if (previous != null && !previous.equals(expression)) {
      errors.add(new ErrorMessage(program, lineNum, name.getStartPos(),
          "\"" + symbol + "\" is already defined"));
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
      String theLine,
      int tokenPos,
      int tokenStartPos,
      TokenList tokenList) {
    String value = new String(token, 0, tokenPos);
    if (!value.isEmpty() && value.charAt(0) == '\'') {
      value = preprocessCharacterLiteral(value);
    }
    TokenTypes type =
        TokenTypes.matchTokenType(
            value, instructions == null ? InstructionDefaults.get() : instructions);
    if (type == TokenTypes.ERROR) {
      errors.add(
          new ErrorMessage(
              program, line, tokenStartPos, theLine + "\nInvalid language element: " + value));
    }
    Token toke = new Token(type, value, program, line, tokenStartPos);
    tokenList.add(toke);
  }

  // If passed a candidate character literal, attempt to translate it into integer constant.
  // If the translation fails, return original value.
  private String preprocessCharacterLiteral(String value) {
    // must start and end with quote and have something in between
    if (value.length() < 3 || value.charAt(0) != '\'' || value.charAt(value.length() - 1) != '\'') {
      return value;
    }
    String quotesRemoved = value.substring(1, value.length() - 1);
    // if not escaped, then if one character left return its value else return original.
    if (quotesRemoved.charAt(0) != '\\') {
      return (quotesRemoved.length() == 1) ? Integer.toString(quotesRemoved.charAt(0)) : value;
    }
    // now we know it is escape sequence and have to decode which of the 8: ',",\,n,t,b,r,f
    if (quotesRemoved.length() == 2) {
      int escapedCharacterIndex = escapedCharacters.indexOf(quotesRemoved.charAt(1));
      return (escapedCharacterIndex >= 0) ? escapedCharactersValues[escapedCharacterIndex] : value;
    }
    // last valid possibility is 3 digit octal code 000 through 377
    if (quotesRemoved.length() == 4) {
      try {
        int intValue = Integer.parseInt(quotesRemoved.substring(1), 8);
        if (intValue >= 0 && intValue <= 255) {
          return Integer.toString(intValue);
        }
      } catch (NumberFormatException ignored) {
      } // if not valid octal, will fall through and reject
    }
    return value;
  }
}
