package edu.missouristate.mars.assembler;

import edu.missouristate.mars.ErrorList;
import edu.missouristate.mars.ErrorMessage;
import edu.missouristate.mars.MIPSprogram;
import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.api.AssemblySymbolMap.Kind;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import edu.missouristate.mars.core.AssemblyContext;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.instructions.Instruction;
import edu.missouristate.mars.util.Binary;
import java.util.ArrayList;
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

/** First assembly pass, owning per-file directive and symbol-processing state. */
final class FirstPassProcessor {
  final AssemblyContext context;
  final Runnable checkCancellation;

  FirstPassProcessor(AssemblyContext context, Runnable checkCancellation) {
    this.context = context;
    this.checkCancellation = checkCancellation;
  }

  private final Parser parser = new Parser(this);
  ErrorList errors;
  boolean inDataSegment; // status maintained by parser
  boolean inMacroSegment; // status maintained by parser, true if in
  // macro definition segment
  private int externAddress;
  private boolean autoAlign;
  private Directives currentDirective;
  private Directives dataDirective;
  MIPSprogram fileCurrentlyBeingAssembled;
  private TokenList globalDeclarationList;
  UserKernelAddressSpace textAddress;
  private UserKernelAddressSpace dataAddress;
  private DataSegmentForwardReferences currentFileDataSegmentForwardReferences;

  /** Builds symbol tables, applies directives and resolves forward data references. */
  ErrorList process(
      ArrayList<MIPSprogram> tokenizedProgramFiles,
      boolean extendedAssemblerEnabled,
      boolean warningsAreErrors)
      throws ProcessingException {
    if (tokenizedProgramFiles == null || tokenizedProgramFiles.isEmpty()) {
      if (context.session() != null) {
        throw new IllegalArgumentException("At least one source file is required");
      }
      return null;
    }
    for (MIPSprogram program : tokenizedProgramFiles) {
      if (!context.sharesSession(program.assemblyContext())) {
        throw new IllegalArgumentException(
            "All source files must belong to the assembler's session");
      }
    }
    checkCancellation.run();
    context.beginAssembly(tokenizedProgramFiles.getFirst());
    textAddress =
        new UserKernelAddressSpace(
            context.memory().textBaseAddress, context.memory().kernelTextBaseAddress);
    dataAddress =
        new UserKernelAddressSpace(
            context.memory().dataBaseAddress, context.memory().kernelDataBaseAddress);
    externAddress = context.memory().externBaseAddress;
    currentFileDataSegmentForwardReferences = new DataSegmentForwardReferences();
    DataSegmentForwardReferences accumulatedDataSegmentForwardReferences =
        new DataSegmentForwardReferences();
    context.symbols().clear();
    context.clearMemory();
    this.errors = context.newErrorList();
    if (context.debug()) {
      System.out.println("Assembler first pass begins:");
    }
    // PROCESS THE FIRST ASSEMBLY PASS FOR ALL SOURCE FILES BEFORE PROCEEDING
    // TO SECOND PASS. THIS ASSURES ALL SYMBOL TABLES ARE CORRECTLY BUILT.
    // THERE IS ONE GLOBAL SYMBOL TABLE (for identifiers declared .globl) PLUS
    // ONE LOCAL SYMBOL TABLE FOR EACH SOURCE FILE.
    for (MIPSprogram programFile : tokenizedProgramFiles) {
      checkCancellation.run();
      if (errors.errorLimitExceeded()) {
        break;
      }
      this.fileCurrentlyBeingAssembled = programFile;
      // List of labels declared ".globl". new list for each file assembled
      this.globalDeclarationList = new TokenList();
      // Parser begins by default in text segment until directed otherwise.
      this.inDataSegment = false;
      // Macro segment will be started by .macro directive
      this.inMacroSegment = false;
      // Default is to align data from directives on appropriate boundary (word, half, byte)
      // This can be turned off for remainder of current data segment with ".align 0"
      this.autoAlign = true;
      // Default data directive is .word for 4 byte data items
      this.dataDirective = Directives.WORD;
      // Clear out (initialize) symbol table related structures.
      fileCurrentlyBeingAssembled.getLocalSymbolTable().clear();
      currentFileDataSegmentForwardReferences.clear();
      // sourceList is an ArrayList of String objects, one per source line.
      // tokenList is an ArrayList of TokenList objects, one per source line;
      // each ArrayList in tokenList consists of Token objects.
      ArrayList<SourceLine> sourceLineList = fileCurrentlyBeingAssembled.getSourceLineList();
      ArrayList<TokenList> tokenList = fileCurrentlyBeingAssembled.getTokenList();
      ArrayList<ProgramStatement> parsedList = fileCurrentlyBeingAssembled.createParsedList();
      // each file keeps its own macro definitions
      MacroPool macroPool = fileCurrentlyBeingAssembled.createMacroPool();
      // FIRST PASS OF ASSEMBLER VERIFIES SYNTAX, GENERATES SYMBOL TABLE,
      // INITIALIZES DATA SEGMENT
      ArrayList<ProgramStatement> statements;
      for (int i = 0; i < tokenList.size(); i++) {
        checkCancellation.run();
        if (errors.errorLimitExceeded()) {
          break;
        }
        for (int z = 0; z < tokenList.get(i).size(); z++) {
          Token t = tokenList.get(i).get(z);
          // record this token's original source program and line #. Differs from final, if .include
          // used
          t.setOriginal(
              sourceLineList.get(i).getMIPSprogram(), sourceLineList.get(i).getLineNumber());
        }
        statements =
            parser.parseLine(
                tokenList.get(i),
                sourceLineList.get(i),
                extendedAssemblerEnabled);
        if (statements != null) {
          parsedList.addAll(statements);
        }
      }
      if (inMacroSegment) {
        errors.add(
            new ErrorMessage(
                fileCurrentlyBeingAssembled,
                fileCurrentlyBeingAssembled.getLocalMacroPool().getCurrent().getFromLine(),
                0,
                "Macro started but not ended (no .end_macro directive)"));
      }
      // move ".globl" symbols from local symtab to global
      this.transferGlobals();
      // Attempt to resolve forward label references that were discovered in operand fields
      // of data segment directives in current file. Those that are not resolved after this
      // call are either references to global labels not seen yet, or are undefined.
      // Cannot determine which until all files are parsed, so copy unresolved entries
      // into accumulated list and clear out this one for re-use with the next source file.
      currentFileDataSegmentForwardReferences.resolve(
          fileCurrentlyBeingAssembled.getLocalSymbolTable());
      accumulatedDataSegmentForwardReferences.add(currentFileDataSegmentForwardReferences);
      currentFileDataSegmentForwardReferences.clear();
    } // end of first-pass loop for each MIPSprogram

    // Have processed all source files. Attempt to resolve any remaining forward label
    // references from global symbol table. Those that remain unresolved are undefined
    // and require error message.
    accumulatedDataSegmentForwardReferences.resolve(context.symbols());
    accumulatedDataSegmentForwardReferences.generateErrorMessages(errors);

    // Throw collection of errors accumulated through the first pass.
    if (errors.errorsOccurred()) {
      throw new ProcessingException(errors);
    }
    return errors;
  }

  // //////////////////////////////////////////////////////////////////////
  // Will check for duplicate text addresses, which can happen inadvertantly when using
  // operand on .text directive. Will generate error message for each one that occurs.

  void detectLabels(TokenList tokens, Macro current) {
    if (tokenListBeginsWithLabel(tokens)) {
      current.addLabel(tokens.get(0).getValue());
    }
  }

  // Determine whether or not a compact (16-bit) translation from
  // pseudo-instruction to basic instruction can be applied. If
  // the argument is a basic instruction, obviously not. If an
  // extended instruction, we have to be operating under a 16-bit
  // memory model and the instruction has to have defined an
  // alternate compact translation.
  boolean compactTranslationCanBeApplied(ProgramStatement statement) {
    return PseudoInstructionExpander.compact(context, statement);
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // Pre-process the token list for a statement by stripping off any comment.
  // NOTE: the ArrayList parameter is not modified; a new one is cloned and
  // returned.
  // stripComment()

  /**
   * Pre-process the token list for a statement by stripping off any label, if either are present.
   * Any label definition will be recorded in the symbol table. NOTE: the ArrayList parameter will
   * be modified.
   */
  void stripLabels(TokenList tokens, SourceLine physicalLine, Origin caller, Set<String> renamedLabels) {
    if (!tokenListBeginsWithLabel(tokens)) {
      return;
    }
    Token token = tokens.get(0);
    tokens.remove(0);
    tokens.remove(0); // The colon shifted to the first position.
    if (token.getType() == TokenTypes.OPERATOR) {
      // An instruction name was used as a label.
      token.setType(TokenTypes.IDENTIFIER);
    }
    fileCurrentlyBeingAssembled.getLocalSymbolTable().addSymbol(
        token,
        inDataSegment ? dataAddress.get() : textAddress.get(),
        inDataSegment ? Kind.DATA : Kind.TEXT,
        Parser.origin(physicalLine, caller),
        renamedLabels.contains(token.getValue()),
        errors);
  } // parseLabel()

  private boolean tokenListBeginsWithLabel(TokenList tokens) {
    // 2-July-2010. DPS. Remove prohibition of operator names as labels
    if (tokens.size() < 2) {
      return false;
    }
    return (tokens.get(0).getType() == TokenTypes.IDENTIFIER
            || tokens.get(0).getType() == TokenTypes.OPERATOR)
        && tokens.get(1).getType() == TokenTypes.COLON;
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // This source code line is a directive, not a MIPS instruction. Let's carry it out.
  @SuppressWarnings("StatementWithEmptyBody")
  void executeDirective(TokenList tokens, SourceLine physicalLine, Origin caller, Set<String> renamedLabels) {
    Token token = tokens.get(0);
    Directives direct = Directives.matchDirective(token.getValue());
    if (context.debug()) {
      System.out.println("line " + token.getSourceLine() + " is directive " + direct);
    }
    if (direct == null) {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + token.getValue() + "\" directive is invalid or not implemented in MARS"));
    } else if (direct == Directives.EQV) {
      /* EQV added by DPS 11 July 2012 */
      // Do nothing.  This was vetted and processed during tokenizing.
    } else if (direct == Directives.MACRO) {
      if (tokens.size() < 2) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" directive requires at least one argument."));
        return;
      }
      if (tokens.get(1).getType() != TokenTypes.IDENTIFIER) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                tokens.get(1).getStartPos(),
                "Invalid Macro name \"" + tokens.get(1).getValue() + "\""));
        return;
      }
      if (inMacroSegment) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "Nested macros are not allowed"));
        return;
      }
      inMacroSegment = true;
      MacroPool pool = fileCurrentlyBeingAssembled.getLocalMacroPool();
      pool.beginMacro(tokens.get(1));
      for (int i = 2; i < tokens.size(); i++) {
        Token arg = tokens.get(i);
        if (arg.getType() == TokenTypes.RIGHT_PAREN || arg.getType() == TokenTypes.LEFT_PAREN) {
          continue;
        }
        if (!Macro.tokenIsMacroParameter(arg.getValue(), true)) {
          errors.add(
              new ErrorMessage(
                  arg.getSourceMIPSprogram(),
                  arg.getSourceLine(),
                  arg.getStartPos(),
                  "Invalid macro argument '" + arg.getValue() + "'"));
          return;
        }
        pool.getCurrent().addArg(arg.getValue());
      }
    } else if (direct == Directives.END_MACRO) {
      if (tokens.size() > 1) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "invalid text after .END_MACRO"));
        return;
      }
      if (!inMacroSegment) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                ".END_MACRO without .MACRO"));
        return;
      }
      inMacroSegment = false;
      fileCurrentlyBeingAssembled.getLocalMacroPool().commitMacro(token);
    } else if (inMacroSegment) {
      // should not parse lines even directives in macro segment
    } else if (direct == Directives.DATA || direct == Directives.KDATA) {
      this.inDataSegment = true;
      this.autoAlign = true;
      this.dataAddress.setAddressSpace(
          (direct == Directives.DATA) ? this.dataAddress.USER : this.dataAddress.KERNEL);
      if (tokens.size() > 1 && TokenTypes.isIntegerTokenType(tokens.get(1).getType())) {
        this.dataAddress.set(Binary.stringToInt(tokens.get(1).getValue())); // KENV 1/6/05
      }
    } else if (direct == Directives.TEXT || direct == Directives.KTEXT) {
      this.inDataSegment = false;
      this.textAddress.setAddressSpace(
          (direct == Directives.TEXT) ? this.textAddress.USER : this.textAddress.KERNEL);
      if (tokens.size() > 1 && TokenTypes.isIntegerTokenType(tokens.get(1).getType())) {
        this.textAddress.set(Binary.stringToInt(tokens.get(1).getValue())); // KENV 1/6/05
      }
    } else if (direct == Directives.WORD
        || direct == Directives.HALF
        || direct == Directives.BYTE
        || direct == Directives.FLOAT
        || direct == Directives.DOUBLE) {
      this.dataDirective = direct;
      if (passesDataSegmentCheck(token) && tokens.size() > 1) { // DPS
        // 11/20/06, added text segment prohibition
        storeNumeric(tokens, direct, errors);
      }
    } else if (direct == Directives.ASCII || direct == Directives.ASCIIZ) {
      this.dataDirective = direct;
      if (passesDataSegmentCheck(token)) {
        storeStrings(tokens, direct, errors);
      }
    } else if (direct == Directives.ALIGN) {
      if (passesDataSegmentCheck(token)) {
        if (tokens.size() != 2) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + token.getValue() + "\" requires one operand"));
          return;
        }
        if (!TokenTypes.isIntegerTokenType(tokens.get(1).getType())
            || Binary.stringToInt(tokens.get(1).getValue()) < 0) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + token.getValue() + "\" requires a non-negative integer"));
          return;
        }
        int value = Binary.stringToInt(tokens.get(1).getValue()); // KENV 1/6/05
        if (value == 0) {
          this.autoAlign = false;
        } else {
          this.dataAddress.set(
              this.alignToBoundary(this.dataAddress.get(), (int) Math.pow(2, value)));
        }
      }
    } else if (direct == Directives.SPACE) {
      if (passesDataSegmentCheck(token)) {
        if (tokens.size() != 2) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + token.getValue() + "\" requires one operand"));
          return;
        }
        if (!TokenTypes.isIntegerTokenType(tokens.get(1).getType())
            || Binary.stringToInt(tokens.get(1).getValue()) < 0) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + token.getValue() + "\" requires a non-negative integer"));
          return;
        }
        int value = Binary.stringToInt(tokens.get(1).getValue()); // KENV 1/6/05
        this.dataAddress.increment(value);
      }
    } else if (direct == Directives.EXTERN) {
      if (tokens.size() != 3) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" directive requires two operands (label and size)."));
        return;
      }
      if (!TokenTypes.isIntegerTokenType(tokens.get(2).getType())
          || Binary.stringToInt(tokens.get(2).getValue()) < 0) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" requires a non-negative integer size"));
        return;
      }
      int size = Binary.stringToInt(tokens.get(2).getValue());
      // If label already in global symtab, do nothing. If not, add it right now.
      if (context.symbols().getAddress(tokens.get(1).getValue()) == SymbolTable.NOT_FOUND) {
        var name = tokens.get(1);
        context.symbols().addSymbol(
            name, externAddress, Kind.EXTERNAL, Parser.origin(physicalLine, caller),
            renamedLabels.contains(name.getValue()), errors);
        this.externAddress += size;
      }
    } else if (direct == Directives.SET) {
      errors.add(
          new ErrorMessage(
              ErrorMessage.WARNING,
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "MARS currently ignores the .set directive."));
    } else if (direct == Directives.GLOBL) {
      if (tokens.size() < 2) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" directive requires at least one argument."));
        return;
      }
      // SPIM limits .globl list to one label, why not extend it to a list?
      for (int i = 1; i < tokens.size(); i++) {
        // Add it to a list of labels to be processed at the end of the
        // pass. At that point, transfer matching symbol definitions from
        // local symbol table to global symbol table.
        Token label = tokens.get(i);
        if (label.getType() != TokenTypes.IDENTIFIER) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + token.getValue() + "\" directive argument must be label."));
          return;
        }
        globalDeclarationList.add(label);
      }
    } else {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + token.getValue() + "\" directive recognized but not yet implemented."));
    }
  } // executeDirective()

  // //////////////////////////////////////////////////////////////////////////////
  // Process the list of .globl labels, if any, declared and defined in this file.
  // We'll just move their symbol table entries from local symbol table to global
  // symbol table at the end of the first assembly pass.
  private void transferGlobals() {
    for (int i = 0; i < globalDeclarationList.size(); i++) {
      checkCancellation.run();
      Token label = globalDeclarationList.get(i);
      Symbol symtabEntry =
          fileCurrentlyBeingAssembled.getLocalSymbolTable().getSymbol(label.getValue());
      if (symtabEntry == null) {
        errors.add(
            new ErrorMessage(
                fileCurrentlyBeingAssembled,
                label.getSourceLine(),
                label.getStartPos(),
                "\"" + label.getValue() + "\" declared global label but not defined."));
      } else {
        if (context.symbols().getAddress(label.getValue()) != SymbolTable.NOT_FOUND) {
          errors.add(
              new ErrorMessage(
                  fileCurrentlyBeingAssembled,
                  label.getSourceLine(),
                  label.getStartPos(),
                  "\"" + label.getValue() + "\" already defined as global in a different file."));
        } else {
          fileCurrentlyBeingAssembled.getLocalSymbolTable().removeSymbol(label);
          context
              .symbols()
              .addSymbol(label, symtabEntry.getAddress(), symtabEntry.kind(), symtabEntry.origin(),
                  symtabEntry.macroPrivate(), errors);
        }
      }
    }
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // This source code line, if syntactically correct, is a continuation of a
  // directive list begun on on previous line.
  void executeDirectiveContinuation(TokenList tokens) {
    Directives direct = this.dataDirective;
    if (direct == Directives.WORD
        || direct == Directives.HALF
        || direct == Directives.BYTE
        || direct == Directives.FLOAT
        || direct == Directives.DOUBLE) {
      if (!tokens.isEmpty()) {
        storeNumeric(tokens, direct, errors);
      }
    } else if (direct == Directives.ASCII || direct == Directives.ASCIIZ) {
      if (passesDataSegmentCheck(tokens.get(0))) {
        storeStrings(tokens, direct, errors);
      }
    }
  } // executeDirectiveContinuation()

  // //////////////////////////////////////////////////////////////////////////////////
  // Given token, find the corresponding Instruction object. If token was not
  // recognized as OPERATOR, there is a problem.
  ArrayList<Instruction> matchInstruction(Token token) {
    if (token.getType() != TokenTypes.OPERATOR) {
      if (token.getSourceMIPSprogram().getLocalMacroPool().matchesAnyMacroName(token.getValue())) {
        this.errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "forward reference or invalid parameters for macro \"" + token.getValue() + "\""));
      } else {
        this.errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" is not a recognized operator"));
      }
      return null;
    }
    ArrayList<Instruction> inst = context.instructions().matchOperator(token.getValue());
    if (inst == null) { // This should NEVER happen...
      this.errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "Internal Assembler error: \""
                  + token.getValue()
                  + "\" tokenized OPERATOR then not recognized"));
    }
    return inst;
  } // matchInstruction()

  // //////////////////////////////////////////////////////////////////////////////////
  // Processes the .word/.half/.byte/.float/.double directive.
  // Can also handle "directive continuations", e.g. second or subsequent line
  // of a multiline list, which does not contain the directive token. Just pass the
  // current directive as argument.
  private void storeNumeric(TokenList tokens, Directives directive, ErrorList errors) {
    Token token = tokens.get(0);
    // A double-check; should have already been caught...removed ".word" exemption 11/20/06
    if (!passesDataSegmentCheck(token)) {
      return;
    }
    // Correctly handles case where this is a "directive continuation" line.
    int tokenStart = 0;
    if (token.getType() == TokenTypes.DIRECTIVE) {
      tokenStart = 1;
    }

    // Set byte length in memory of each number (e.g. WORD is 4, BYTE is 1, etc)
    int lengthInBytes = DataTypes.getLengthInBytes(directive);

    // Handle the "value : n" format, which replicates the value "n" times.
    if (tokens.size() == 4 && tokens.get(2).getType() == TokenTypes.COLON) {
      Token valueToken = tokens.get(1);
      Token repetitionsToken = tokens.get(3);
      // DPS 15-jul-08, allow ":" for repetition for all numeric
      // directives (originally just .word)
      // Conditions for correctly-formed replication:
      // (integer directive AND integer value OR floating directive AND
      // (integer value OR floating value))
      // AND integer repetition value
      if (!(Directives.isIntegerDirective(directive)
                  && TokenTypes.isIntegerTokenType(valueToken.getType())
              || Directives.isFloatingDirective(directive)
                  && (TokenTypes.isIntegerTokenType(valueToken.getType())
                      || TokenTypes.isFloatingTokenType(valueToken.getType())))
          || !TokenTypes.isIntegerTokenType(repetitionsToken.getType())) {
        errors.add(
            new ErrorMessage(
                fileCurrentlyBeingAssembled,
                valueToken.getSourceLine(),
                valueToken.getStartPos(),
                "malformed expression"));
        return;
      }
      int repetitions = Binary.stringToInt(repetitionsToken.getValue()); // KENV 1/6/05
      if (repetitions <= 0) {
        errors.add(
            new ErrorMessage(
                fileCurrentlyBeingAssembled,
                repetitionsToken.getSourceLine(),
                repetitionsToken.getStartPos(),
                "repetition factor must be positive"));
        return;
      }
      if (this.inDataSegment) {
        if (this.autoAlign) {
          this.dataAddress.set(this.alignToBoundary(this.dataAddress.get(), lengthInBytes));
        }
        for (int i = 0; i < repetitions; i++) {
          checkCancellation.run();
          if (Directives.isIntegerDirective(directive)) {
            storeInteger(valueToken, directive, errors);
          } else {
            storeRealNumber(valueToken, directive, errors);
          }
        }
      } // WHAT ABOUT .KDATA SEGMENT?
      /* **************************************************************************
       * /****** NOTE of 11/20/06. Below will always throw exception b/c
       * you cannot use MemoryState.set() with text segment addresses and the
       * "not valid address" produced here is misleading. Added data
       * segment check prior to this point, so this "else" will never be
       * executed. I'm leaving it in just in case MARS in the future adds
       * capability of writing to the text segment (e.g. ability to
       * de-assemble a binary value into its corresponding MIPS
       * instruction)
       * <p>
       * else { // not in data segment...which we assume to mean in text
       * segment. try { for (int i=0; i < repetitions; i++) {
       * context.memory().set(this.textAddress.get(),
       * Binary.stringToInt(valueToken.getValue()), lengthInBytes);
       * this.textAddress.increment(lengthInBytes); } } catch
       * (AddressErrorException e) { errors.add(new
       * ErrorMessage(token.getSourceMIPSprogram(), token.getSourceLine(),
       * token.getStartPos(), "\""+this.textAddress.get()+
       * "\" is not a valid text segment address")); } }
       ************************************************************************/
      return;
    }

    // if not in ".word w : n" format, must just be list of one or more values.
    for (int i = tokenStart; i < tokens.size(); i++) {
      checkCancellation.run();
      token = tokens.get(i);
      if (Directives.isIntegerDirective(directive)) {
        storeInteger(token, directive, errors);
      }
      if (Directives.isFloatingDirective(directive)) {
        storeRealNumber(token, directive, errors);
      }
    }
  } // storeNumeric()

  // //////////////////////////////////////////////////////////////////////////////
  // Store integer value given integer (word, half, byte) directive.
  // Called by storeNumeric()
  // NOTE: The token itself may be a label, in which case the correct action is
  // to store the address of that label (into however many bytes specified).
  private void storeInteger(Token token, Directives directive, ErrorList errors) {
    int lengthInBytes = DataTypes.getLengthInBytes(directive);
    if (TokenTypes.isIntegerTokenType(token.getType())) {
      int value = Binary.stringToInt(token.getValue());
      int fullvalue = value;
      // DPS 4-Jan-2013.  Overriding 6-Jan-2005 KENV changes.
      // If value is out of range for the directive, will simply truncate
      // the leading bits (includes sign bits). This is what SPIM does.
      // But will issue a warning (not error) which SPIM does not do.
      if (directive == Directives.BYTE) {
        value = value & 0x000000FF;
      } else if (directive == Directives.HALF) {
        value = value & 0x0000FFFF;
      }

      if (DataTypes.outOfRange(directive, fullvalue)) {
        errors.add(
            new ErrorMessage(
                ErrorMessage.WARNING,
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\""
                    + token.getValue()
                    + "\" is out-of-range for a signed value and possibly truncated"));
      }
      if (this.inDataSegment) {
        writeToDataSegment(value, lengthInBytes, token, errors);
      }
      /* *****
       * NOTE of 11/20/06. "try" below will always throw exception b/c you
       * cannot use MemoryState.set() with text segment addresses and the
       * "not valid address" produced here is misleading. Added data
       * segment check prior to this point, so this "else" will never be
       * executed. I'm leaving it in just in case MARS in the future adds
       * capability of writing to the text segment (e.g. ability to
       * de-assemble a binary value into its corresponding MIPS
       * instruction)
       ********/
      else {
        try {
          context.memory().set(this.textAddress.get(), value, lengthInBytes);
        } catch (AddressErrorException e) {
          errors.add(
              new ErrorMessage(
                  token.getSourceMIPSprogram(),
                  token.getSourceLine(),
                  token.getStartPos(),
                  "\"" + this.textAddress.get() + "\" is not a valid text segment address"));
          return;
        }
        this.textAddress.increment(lengthInBytes);
      }
    } // end of "if integer token type"
    else if (token.getType() == TokenTypes.IDENTIFIER) {
      if (this.inDataSegment) {
        int value =
            fileCurrentlyBeingAssembled
                .getLocalSymbolTable()
                .getAddressLocalOrGlobal(token.getValue());
        if (value == SymbolTable.NOT_FOUND) {
          // Record value 0 for now, then set up backpatch entry
          int dataAddress = writeToDataSegment(0, lengthInBytes, token, errors);
          currentFileDataSegmentForwardReferences.add(dataAddress, lengthInBytes, token);
        } else { // label already defined, so write its address
          writeToDataSegment(value, lengthInBytes, token, errors);
        }
      } // Data segment check done previously, so this "else" will not be.
      // See 11/20/06 note above.
      else {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\""
                    + token.getValue()
                    + "\" label as directive operand not permitted in text segment"));
      }
    } // end of "if label"
    else {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + token.getValue() + "\" is not a valid integer constant or label"));
    }
  } // storeInteger

  // //////////////////////////////////////////////////////////////////////////////
  // Store real (fixed or floating point) value given floating (float, double) directive.
  // Called by storeNumeric()
  private void storeRealNumber(Token token, Directives directive, ErrorList errors) {
    int lengthInBytes = DataTypes.getLengthInBytes(directive);
    double value;

    if (TokenTypes.isIntegerTokenType(token.getType())
        || TokenTypes.isFloatingTokenType(token.getType())) {
      try {
        value = Double.parseDouble(token.getValue());
      } catch (NumberFormatException nfe) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" is not a valid floating point constant"));
        return;
      }
      if (DataTypes.outOfRange(directive, value)) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" is an out-of-range value"));
        return;
      }
    } else {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + token.getValue() + "\" is not a valid floating point constant"));
      return;
    }

    // Value has been validated; let's store it.

    if (directive == Directives.FLOAT) {
      writeToDataSegment(Float.floatToIntBits((float) value), lengthInBytes, token, errors);
    }
    if (directive == Directives.DOUBLE) {
      writeDoubleToDataSegment(value, token, errors);
    }
  } // storeRealNumber

  // //////////////////////////////////////////////////////////////////////////////////
  // Use directive argument to distinguish between ASCII and ASCIIZ. The
  // latter stores a terminating null byte. Can handle a list of one or more
  // strings on a single line.
  private void storeStrings(TokenList tokens, Directives direct, ErrorList errors) {
    Token token;
    // Correctly handles case where this is a "directive continuation" line.
    int tokenStart = 0;
    if (tokens.get(0).getType() == TokenTypes.DIRECTIVE) {
      tokenStart = 1;
    }
    for (int i = tokenStart; i < tokens.size(); i++) {
      checkCancellation.run();
      token = tokens.get(i);
      if (token.getType() != TokenTypes.QUOTED_STRING) {
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "\"" + token.getValue() + "\" is not a valid character string"));
      } else {
        String quote = token.getValue();
        char theChar;
        for (int j = 1; j < quote.length() - 1; j++) {
          theChar = quote.charAt(j);
          if (theChar == '\\') {
            theChar = quote.charAt(++j);
            switch (theChar) {
              case 'n':
                theChar = '\n';
                break;
              case 't':
                theChar = '\t';
                break;
              case 'r':
                theChar = '\r';
                break;
              case '\\':
                theChar = '\\';
                break;
              case '\'':
                theChar = '\'';
                break;
              case '"':
                theChar = '"';
                break;
              case 'b':
                theChar = '\b';
                break;
              case 'f':
                theChar = '\f';
                break;
              case '0':
                theChar = '\0';
                break;
                // Not implemented: \ n = octal character (n is number)
                // \ x n = hex character (n is number)
                // \ u n = unicode character (n is number)
                // There are of course no spaces in these escape
                // codes...
            }
          }
          try {
            context.memory().set(this.dataAddress.get(), (int) theChar, DataTypes.CHAR_SIZE);
          } catch (AddressErrorException e) {
            errors.add(
                new ErrorMessage(
                    token.getSourceMIPSprogram(),
                    token.getSourceLine(),
                    token.getStartPos(),
                    "\"" + this.dataAddress.get() + "\" is not a valid data segment address"));
          }
          this.dataAddress.increment(DataTypes.CHAR_SIZE);
        }
        if (direct == Directives.ASCIIZ) {
          try {
            context.memory().set(this.dataAddress.get(), 0, DataTypes.CHAR_SIZE);
          } catch (AddressErrorException e) {
            errors.add(
                new ErrorMessage(
                    token.getSourceMIPSprogram(),
                    token.getSourceLine(),
                    token.getStartPos(),
                    "\"" + this.dataAddress.get() + "\" is not a valid data segment address"));
          }
          this.dataAddress.increment(DataTypes.CHAR_SIZE);
        }
      }
    }
  } // storeStrings()

  // //////////////////////////////////////////////////////////////////////////////////
  // Simply check to see if we are in data segment. Generate error if not.
  private boolean passesDataSegmentCheck(Token token) {
    if (!this.inDataSegment) {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + token.getValue() + "\" directive cannot appear in text segment"));
      return false;
    } else {
      return true;
    }
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // Writes the given int value into current data segment address. Works for
  // all the integer types plus float (caller is responsible for doing floatToIntBits).
  // Returns address at which the value was stored.
  private int writeToDataSegment(int value, int lengthInBytes, Token token, ErrorList errors) {
    if (this.autoAlign) {
      this.dataAddress.set(this.alignToBoundary(this.dataAddress.get(), lengthInBytes));
    }
    try {
      context.memory().set(this.dataAddress.get(), value, lengthInBytes);
    } catch (AddressErrorException e) {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + this.dataAddress.get() + "\" is not a valid data segment address"));
      return this.dataAddress.get();
    }
    int address = this.dataAddress.get();
    this.dataAddress.increment(lengthInBytes);
    return address;
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // Writes the given double value into current data segment address. Works
  // only for DOUBLE floating
  // point values -- Memory class doesn't have method for writing 8 bytes, so
  // use setWord twice.
  private void writeDoubleToDataSegment(double value, Token token, ErrorList errors) {
    int lengthInBytes = DataTypes.DOUBLE_SIZE;
    if (this.autoAlign) {
      this.dataAddress.set(this.alignToBoundary(this.dataAddress.get(), lengthInBytes));
    }
    try {
      context.memory().setDouble(this.dataAddress.get(), value);
    } catch (AddressErrorException e) {
      errors.add(
          new ErrorMessage(
              token.getSourceMIPSprogram(),
              token.getSourceLine(),
              token.getStartPos(),
              "\"" + this.dataAddress.get() + "\" is not a valid data segment address"));
      return;
    }
    this.dataAddress.increment(lengthInBytes);
  }

  // //////////////////////////////////////////////////////////////////////////////////
  // If address is multiple of byte boundary, returns address. Otherwise, returns address
  // which is next higher multiple of the byte boundary. Used for aligning data segment.
  // For instance if args are 6 and 4, returns 8 (next multiple of 4 higher than 6).
  // NOTE: it will fix any symbol table entries for this address too. See else part.
  private int alignToBoundary(int address, int byteBoundary) {
    int remainder = address % byteBoundary;
    if (remainder == 0) {
      return address;
    } else {
      int alignedAddress = address + byteBoundary - remainder;
      fileCurrentlyBeingAssembled
          .getLocalSymbolTable()
          .fixSymbolTableAddress(address, alignedAddress);
      return alignedAddress;
    }
  }

  // ///////////////////////////////////////////////////////////////////////////////////
  // Private class used as Comparator to sort the final ArrayList of
  // ProgramStatements.
  // Sorting is based on unsigned integer value of
  // ProgramStatement.getAddress()
  // ///////////////////////////////////////////////////////////////////////////////////
  // Private class to simultaneously track addresses in both user and kernel
  // address spaces.
  // Instantiate one for data segment and one for text segment.
  static final class UserKernelAddressSpace {
    final int[] address;
    int currentAddressSpace;
    private final int USER = 0, KERNEL = 1;

    // Initially use user address space, not kernel.
    private UserKernelAddressSpace(int userBase, int kernelBase) {
      address = new int[2];
      address[USER] = userBase;
      address[KERNEL] = kernelBase;
      currentAddressSpace = USER;
    }

    int get() {
      return address[currentAddressSpace];
    }

    private void set(int value) {
      address[currentAddressSpace] = value;
    }

    void increment(int increment) {
      address[currentAddressSpace] += increment;
    }

    private void setAddressSpace(int addressSpace) {
      if (addressSpace == USER || addressSpace == KERNEL) {
        currentAddressSpace = addressSpace;
      } else {
        throw new IllegalArgumentException();
      }
    }
  }

  // //////////////////////////////////////////////////////////////////////////
  // Handy class to handle forward label references appearing as data
  // segment operands. This is needed because the data segment is comletely
  // processed by the end of the first assembly pass, and its directives may
  // contain labels as operands. When this occurs, the label's associated
  // address becomes the operand value. If it is a forward reference, we will
  // save the necessary information in this object for finding and patching in
  // the correct address at the end of the first pass (for this file or for all
  // files if more than one).
  //
  // If such a parsed label refers to a local or global label not defined yet,
  // pertinent information is added to this object:
  // - memory address that needs the label's address,
  // - number of bytes (addresses are 4 bytes but may be used with any of
  // the integer directives: .word, .half, .byte)
  // - the label's token. Normally need only the name but error message needs more.
  private class DataSegmentForwardReferences {
    private final ArrayList<DataSegmentForwardReference> forwardReferenceList;

    private DataSegmentForwardReferences() {
      forwardReferenceList = new ArrayList<>();
    }

    private int size() {
      return forwardReferenceList.size();
    }

    // Add a new forward reference entry. Client must supply the following:
    // - memory address to receive the label's address once resolved
    // - number of address bytes to store (1 for .byte, 2 for .half, 4 for .word)
    // - the label's token. All its information will be needed if error message generated.
    private void add(int patchAddress, int length, Token token) {
      forwardReferenceList.add(new DataSegmentForwardReference(patchAddress, length, token));
    }

    // Add the entries of another DataSegmentForwardReferences object to this one.
    // Can be used at the end of each source file to dump all unresolved references
    // into a common list to be processed after all source files parsed.
    private void add(DataSegmentForwardReferences another) {
      forwardReferenceList.addAll(another.forwardReferenceList);
    }

    // Clear out the list. Allows you to re-use it.
    private void clear() {
      forwardReferenceList.clear();
    }

    // Will traverse the list of forward references, attempting to resolve them.
    // For each entry it will first search the provided local symbol table and
    // failing that, the global one. If passed the global symbol table, it will
    // perform a second, redundant, search. If search is successful, the patch
    // is applied and the forward reference removed. If search is not successful,
    // the forward reference remains (it is either undefined or a global label
    // defined in a file not yet parsed).
    private int resolve(SymbolTable localSymtab) {
      int count = 0;
      int labelAddress;
      DataSegmentForwardReference entry;
      for (int i = 0; i < forwardReferenceList.size(); i++) {
        checkCancellation.run();
        entry = forwardReferenceList.get(i);
        labelAddress = localSymtab.getAddressLocalOrGlobal(entry.token.getValue());
        if (labelAddress != SymbolTable.NOT_FOUND) {
          // patch address has to be valid b/c we already stored there...
          try {
            context.memory().set(entry.patchAddress, labelAddress, entry.length);
          } catch (AddressErrorException ignored) {
          }
          forwardReferenceList.remove(i);
          i--; // needed because removal shifted the remaining list indices down
          count++;
        }
      }
      return count;
    }

    // Call this when you are confident that remaining list entries are to
    // undefined labels.
    private void generateErrorMessages(ErrorList errors) {
      DataSegmentForwardReference entry;
      for (DataSegmentForwardReference dataSegmentForwardReference : forwardReferenceList) {
        checkCancellation.run();
        entry = dataSegmentForwardReference;
        errors.add(
            new ErrorMessage(
                entry.token.getSourceMIPSprogram(),
                entry.token.getSourceLine(),
                entry.token.getStartPos(),
                "Symbol \"" + entry.token.getValue() + "\" not found in symbol table."));
      }
    }

    // inner-inner class to hold each entry of the forward reference list.
    private record DataSegmentForwardReference(int patchAddress, int length, Token token) {}
  }
}
