package edu.missouristate.mars.assembler;

import edu.missouristate.mars.ErrorMessage;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.AssemblySymbolMap.MacroCall;
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import edu.missouristate.mars.api.InstructionSourceMap.Location;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import edu.missouristate.mars.api.InstructionSourceMap.SourceOperand;
import edu.missouristate.mars.mips.instructions.ExtendedInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.mips.instructions.basic.InstrMfc0;
import edu.missouristate.mars.mips.instructions.basic.InstrMtc0;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

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

/** Parses tokenized source lines and expands source macros within one assembly pass. */
final class Parser {
  private final FirstPassProcessor pass;

  Parser(FirstPassProcessor pass) {
    this.pass = pass;
  }

  ArrayList<ProgramStatement> parseLine(
      TokenList tokenList, SourceLine physicalLine, boolean extendedAssemblerEnabled) {
    return parseLine(
        tokenList, physicalLine.getSource(), physicalLine.getLineNumber(),
        extendedAssemblerEnabled, physicalLine, null, Set.of());
  }

  private ArrayList<ProgramStatement> parseLine(
      TokenList tokenList, String source, int sourceLineNumber, boolean extendedAssemblerEnabled,
      SourceLine physicalLine, Origin caller, Set<String> renamedLabels) {

    ArrayList<ProgramStatement> ret = new ArrayList<>();

    ProgramStatement programStatement;
    TokenList tokens = stripComment(tokenList);

    // Labels should not be processed in macro definition segment.
    MacroPool macroPool = pass.fileCurrentlyBeingAssembled.getLocalMacroPool();
    if (pass.inMacroSegment) {
      pass.detectLabels(tokens, macroPool.getCurrent());
    } else {
      pass.stripLabels(tokens, physicalLine, caller, renamedLabels);
    }
    if (tokens.isEmpty()) {
      return null;
    }
    // Grab first (operator) token...
    Token token = tokens.get(0);
    TokenTypes tokenType = token.getType();

    // Let's handle the directives here...
    if (tokenType == TokenTypes.DIRECTIVE) {
      pass.executeDirective(tokens, physicalLine, caller, renamedLabels);
      return null;
    }

    // don't parse if in macro segment
    if (pass.inMacroSegment) {
      return null;
    }

    // SPIM-style macro calling:
    TokenList parenFreeTokens = tokens;
    Token closingParenthesis = null;
    if (Macro.hasCallParentheses(tokens)) {
      parenFreeTokens = (TokenList) tokens.clone();
      closingParenthesis = tokens.get(tokens.size() - 1);
      parenFreeTokens.remove(tokens.size() - 1);
      parenFreeTokens.remove(1);
    }
    Macro macro =
        macroPool.getMatchingMacro(
            parenFreeTokens, sourceLineNumber); // parenFreeTokens.get(0).getSourceLine());

    // expand macro if this line is a macro expansion call
    if (macro != null) {
      tokens = parenFreeTokens;
      // get unique id for this expansion
      int counter = macroPool.getNextCounter();
      if (macroPool.pushOnCallStack(token)) {
        pass.errors.add(
            new ErrorMessage(token, "Detected a macro expansion loop (recursive reference). "));
      } else {
        //                for (int i = macro.getFromLine() + 1; i < macro.getToLine(); i++) {
        //                   String substituted = macro.getSubstitutedLine(i, tokens, counter,
        // pass.errors);
        //                   TokenList tokenList2 =
        // pass.fileCurrentlyBeingAssembled.getTokenizer().tokenizeLine(
        //                      i, substituted, pass.errors);
        //                   // If token list getProcessedLine() is not empty, then .eqv was
        // performed and it contains the modified source.
        //                	// Put it into the line to be parsed, so it will be displayed properly in
        // text segment display. DPS 23 Jan 2013
        //                   if (tokenList2.getProcessedLine().length() > 0)
        //                      substituted = tokenList2.getProcessedLine();
        //                   // recursively parse lines of expanded macro
        //                   ArrayList<ProgramStatement> statements = parseLine(tokenList2, "<" +
        // (i-macro.getFromLine()+macro.getOriginalFromLine()) + "> "
        //                      + substituted.trim(), sourceLineNumber, extendedAssemblerEnabled);
        //                   if (statements != null)
        //                      ret.addAll(statements);
        //                }
        var invocation = origin(physicalLine, caller);
        var physicalLines = pass.fileCurrentlyBeingAssembled.getSourceLineList();
        var call = new SourceToken(invocation.location(), token.writtenOrigin().getOriginalStartPos());
        var spelling = token.equivalenceSource();
        if (spelling == null) {
          spelling = token.diagnosticSite();
        }
        var arguments = new ArrayList<Integer>(tokens.size() - 1);
        for (int i = 1; i < tokens.size(); i++) {
          arguments.add(tokens.get(i).writtenOrigin().getOriginalStartPos());
        }
        Integer closingColumn = closingParenthesis == null
            ? null : closingParenthesis.writtenOrigin().getOriginalStartPos();
        var occurrence = new MacroCall(
            macro.definitionSite(), macro.getName(), macro.getArgs(), arguments, closingColumn,
            spelling.equals(call) ? null : spelling);
        pass.macroCalls.computeIfAbsent(call, ignored -> new HashSet<>()).add(occurrence);
        for (int i = macro.getFromLine() + 1; i < macro.getToLine(); i++) {
          pass.checkCancellation.run();
          if (pass.errors.errorLimitExceeded()) {
            break;
          }
          int errorCount = pass.errors.errorCount();
          var expanded = macro.expandLine(i, tokens, counter, renamedLabels, pass.errors);
          String substituted = expanded.text();
          TokenList tokenList2 =
              pass.fileCurrentlyBeingAssembled
                  .getTokenizer()
                  .tokenizeMacroLine(i, expanded, pass.errors, pass.checkCancellation);
          if (pass.errors.errorCount() > errorCount) {
            continue;
          }

          // If token list getProcessedLine() is not empty, then .eqv was performed and it contains
          // the modified source.
          // Put it into the line to be parsed, so it will be displayed properly in text segment
          // display. DPS 23 Jan 2013
          if (!tokenList2.getProcessedLine().isEmpty()) {
            substituted = tokenList2.getProcessedLine();
          }

          // recursively parse lines of expanded macro
          ArrayList<ProgramStatement> statements =
              parseLine(
                  tokenList2,
                  "<"
                      + (i - macro.getFromLine() + macro.getOriginalFromLine())
                      + "> "
                      + substituted.trim(),
                  sourceLineNumber,
                  extendedAssemblerEnabled,
                  physicalLines.get(i - 1),
                  invocation,
                  expanded.renamedLabels());
          if (statements != null) {
            ret.addAll(statements);
          }
        }
        macroPool.popFromCallStack();
      }
      return ret;
    }

    // DPS 14-July-2008
    // Yet Another Hack: detect unrecognized directive. MARS recognizes the same directives
    // as SPIM but other MIPS assemblers recognize additional directives. Compilers such
    // as MIPS-directed GCC generate assembly code containing these directives. We'd like
    // the opportunity to ignore them and continue. Tokenizer would categorize an unrecognized
    // directive as an TokenTypes.IDENTIFIER because it would not be matched as a directive and
    // MIPS labels can start with '.' NOTE: this can also be handled by including the
    // ignored directive in the Directives.java list. There is already a mechanism in place
    // for generating a warning there. But I cannot anticipate the names of all directives
    // so this will catch anything, including a misspelling of a valid directive (which is
    // a nice thing to do).
    if (tokenType == TokenTypes.IDENTIFIER && token.getValue().charAt(0) == '.') {
      pass.errors.add(
          new ErrorMessage(
              ErrorMessage.WARNING,
              token,
              "MARS does not recognize the " + token.getValue() + " directive.  Ignored."));
      return null;
    }

    // The directives with lists (.byte, .double, .float, .half, .word, .ascii, .asciiz)
    // should be able to extend the list over several lines. Since this method assembles
    // only one source line, state information must be stored from one invocation to
    // the next, to sense the pass.context of this continuation line. That state information
    // is contained in this.dataDirective (the current data directive).
    //
    if (pass.inDataSegment && TokenTypes.startsDataContinuation(tokenType)) {
      pass.executeDirectiveContinuation(tokens);
      return null;
    }

    // If we are in the text segment, the variable "token" must now refer to
    // an OPERATOR
    // token. If not, it is either a syntax error or the specified operator
    // is not
    // yet implemented.
    if (!pass.inDataSegment) {
      ArrayList<Instruction> instrMatches = pass.matchInstruction(token);
      if (instrMatches == null) {
        return ret;
      }
      // OK, we've got an operator match, let's check the operands.
      Instruction inst =
          OperandFormat.bestOperandMatch(tokens, instrMatches, pass.context.bareMachineEnabled());
      // Here's the place to flag use of extended (pseudo) instructions
      // when setting disabled.
      if (inst instanceof ExtendedInstruction && !extendedAssemblerEnabled) {
        pass.errors.add(
            new ErrorMessage(
                token,
                "Extended (pseudo) instruction or format not permitted.  See Settings."));
      }
      if (OperandFormat.tokenOperandMatch(
          tokens, inst, pass.errors, pass.context.bareMachineEnabled())) {
        var instructionOrigin = origin(physicalLine, caller);
        programStatement =
            new ProgramStatement(
                pass.fileCurrentlyBeingAssembled,
                source,
                tokenList,
                tokens,
                inst,
                pass.textAddress.get(),
                sourceLineNumber,
                instructionOrigin,
                sourceOperands(tokens, inst, instructionOrigin.location()));
        // instruction length is 4 for all basic instruction, varies for extended instruction
        // Modified to permit use of compact expansion if address fits
        // in 15 bits. DPS 4-Aug-2009
        int instLength =
            inst instanceof ExtendedInstruction ext
                ? ext.getInstructionLength(pass.context.settings().isDelayedBranchingEnabled())
                : inst.getInstructionLength();
        if (pass.compactTranslationCanBeApplied(programStatement)
            && inst instanceof ExtendedInstruction ext) {
          instLength =
              ext.getCompactInstructionLength(pass.context.settings().isDelayedBranchingEnabled());
        }
        pass.textAddress.increment(instLength);
        ret.add(programStatement);
        return ret;
      }
    }
    return null;
  }

  private static List<SourceOperand> sourceOperands(
      TokenList tokens, Instruction instruction, Location location) {
    ArrayList<SourceOperand> operands = null;
    for (int i = 1; i < tokens.size(); i++) {
      var token = tokens.get(i);
      var written = token.writtenOrigin();
      var arguments = token.argumentSites();
      if (arguments.isEmpty() && token.equivalenceSource() == null && token.getValue().equals(written.getValue())) {
        continue;
      }
      if (operands == null) {
        operands = new ArrayList<>();
      }
      var site = new SourceToken(location, written.getOriginalStartPos());
      boolean systemRegister = i == 2 && (instruction instanceof InstrMfc0 || instruction instanceof InstrMtc0);
      operands.add(new SourceOperand(site, arguments,
          token.getValue(), token.getType(), systemRegister));
    }
    return operands == null ? List.of() : operands;
  }

  static Origin origin(SourceLine line, Origin caller) {
    return new Origin(new Location(Path.of(line.getFilename()), line.getLineNumber()), caller);
  }

  private TokenList stripComment(TokenList tokenList) {
    if (tokenList.isEmpty()) {
      return tokenList;
    }
    TokenList tokens = (TokenList) tokenList.clone();
    // If there is a comment, strip it off.
    int last = tokens.size() - 1;
    if (tokens.get(last).getType() == TokenTypes.COMMENT) {
      tokens.remove(last);
    }
    return tokens;
  }
}
