package edu.missouristate.mars.desktop;

import edu.missouristate.mars.*;
import edu.missouristate.mars.ApplicationInfo;
import edu.missouristate.mars.mips.dump.*;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.desktop.venus.*;
import edu.missouristate.mars.simulator.*;
import edu.missouristate.mars.util.*;
import java.io.*;
import java.util.*;
import javax.swing.*;

// KENV 9/8/2004

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

/**
 * Launch the Mars application
 *
 * @author Pete Sanderson
 * @version December 2009
 */
public class MarsLaunch {

  /**
   * Main takes a number of command line arguments.<br>
   * Usage: Mars [options] filename<br>
   * Valid options (not case sensitive, separate by spaces) are:<br>
   * a -- assemble only, do not simulate<br>
   * ad -- both a and d<br>
   * ae<n> -- terminate MARS with integer exit code <n> if an assemble error occurs.<br>
   * ascii -- display memory or register contents interpreted as ASCII b -- brief - do not display
   * register/memory address along with contents<br>
   * d -- print debugging statements<br>
   * da -- both a and d<br>
   * db -- MIPS delayed branching is enabled.<br>
   * dec -- display memory or register contents in decimal.<br>
   * dump -- dump memory contents to file. Option has 3 arguments, e.g. <br>
   * <code>dump &lt;segment&gt; &lt;format&gt; &lt;file&gt;</code>. Also supports<br>
   * an address range (see <i>m-n</i> below). Current supported <br>
   * segments are <code>.text</code> and <code>.data</code>. Current supported dump formats <br>
   * are <code>Binary</code>, <code>HexText</code>, <code>BinaryText</code>.<br>
   * h -- display help. Use by itself and with no filename</br> hex -- display memory or register
   * contents in hexadecimal (default)<br>
   * ic -- display count of MIPS basic instructions 'executed'"); mc -- set memory configuration.
   * Option has 1 argument, e.g.<br>
   * <code>mc &lt;config$gt;</code>, where &lt;config$gt; is <code>Default</code><br>
   * for the MARS default 32-bit address space, <code>CompactDataAtZero</code> for<br>
   * a 32KB address space with data segment at address 0, or <code>CompactTextAtZero</code><br>
   * for a 32KB address space with text segment at address 0.<br>
   * me -- display MARS messages to standard err instead of standard out. Can separate via
   * redirection.</br> nc -- do not display copyright notice (for cleaner redirected/piped
   * output).</br> np -- No Pseudo-instructions allowed ("ne" will work also).<br>
   * p -- Project mode - assemble all files in the same directory as given file.<br>
   * se<n> -- terminate MARS with integer exit code <n> if a simulation (run) error occurs.<br>
   * sm -- Start execution at Main - Execution will start at program statement globally labeled
   * main.<br>
   * smc -- Self Modifying Code - Program can write and branch to either text or data segment<br>
   * we -- assembler Warnings will be considered Errors<br>
   * <n> -- where <n> is an integer maximum count of steps to simulate.<br>
   * If 0, negative or not specified, there is no maximum.<br>
   * $<reg> -- where <reg> is number or name (e.g. 5, t3, f10) of register whose <br>
   * content to display at end of run. Option may be repeated.<br>
   * <reg_name> -- where <reg_name> is name (e.g. t3, f10) of register whose <br>
   * content to display at end of run. Option may be repeated. $ not required.<br>
   * <m>-<n> -- memory address range from <m> to <n> whose contents to<br>
   * display at end of run. <m> and <n> may be hex or decimal,<br>
   * <m> <= <n>, both must be on word boundary. Option may be repeated.<br>
   * pa -- Program Arguments follow in a space-separated list. This<br>
   * option must be placed AFTER ALL FILE NAMES, because everything<br>
   * that follows it is interpreted as a program argument to be<br>
   * made available to the MIPS program at runtime.<br>
   */
  private boolean simulate;

  private int displayFormat;
  private boolean verbose; // display register name or address along with contents
  private boolean assembleProject; // assemble only the given file or all files in its directory
  private boolean pseudo; // pseudo instructions allowed in source code or not.
  private boolean delayedBranching; // MIPS delayed branching is enabled.
  private boolean warningsAreErrors; // Whether assembler warnings should be considered errors.
  private boolean startAtMain; // Whether to start execution at statement labeled 'main'
  private boolean countInstructions; // Whether to count and report number of instructions executed
  private boolean
      selfModifyingCode; // Whether to allow self-modifying code (e.g. write to text segment)
  private static final int splashDuration = 2000; // time in MS to show splash screen
  private static final int memoryWordsPerLine =
      4; // display 4 memory words, tab separated, per line
  private static final int DECIMAL = 0; // memory and register display format
  private static final int HEXADECIMAL = 1; // memory and register display format
  private static final int ASCII = 2; // memory and register display format
  private ArrayList<String> registerDisplayList;
  private ArrayList<MemoryAddressRange> memoryDisplayList;
  private ArrayList<String> filenameList;
  private MIPSprogram code;
  private int maxSteps;
  private long instructionCount;
  private PrintStream out; // stream for display of command line output
  private ArrayList<String[]> dumpTriples = null; // each element holds 3 arguments for dump option
  private ArrayList<String>
      programArgumentList; // optional program args for MIPS program (becomes argc, argv)
  private int assembleErrorExitCode; // MARS command exit code to return if assemble error occurs
  private int simulateErrorExitCode; // MARS command exit code to return if simulation error occurs

  private final edu.missouristate.mars.desktop.runtime.DesktopSession session;

  public MarsLaunch(String[] args, edu.missouristate.mars.desktop.runtime.DesktopSession session) {
    this.session = session;
    boolean gui = (args.length == 0);
    if (gui) {
      launchIDE();
    } else { // running from command line.
      // assure command mode works in headless environment (generates exception if not)
      System.setProperty("java.awt.headless", "true");
      session.setSyscallEnvironment(session.context().syscallEnvironment);
      simulate = true;
      displayFormat = HEXADECIMAL;
      verbose = true;
      assembleProject = false;
      pseudo = true;
      delayedBranching = false;
      warningsAreErrors = false;
      startAtMain = false;
      countInstructions = false;
      selfModifyingCode = false;
      instructionCount = 0;
      assembleErrorExitCode = 1;
      simulateErrorExitCode = 1;
      registerDisplayList = new ArrayList<>();
      memoryDisplayList = new ArrayList<>();
      filenameList = new ArrayList<>();
      session.setMemoryConfiguration(MemoryConfigurations.getDefaultConfiguration());
      // do NOT use session.getProgram() for command line MARS -- it triggers
      // 'backstep' log.
      code = session.createProgram();
      maxSteps = -1;
      out = System.out;
      if (parseCommandArgs(args)) {
        runCommand();
      }
      System.exit(session.exitCode());
    }
  }

  /////////////////////////////////////////////////////////////
  // Perform any specified dump operations.  See "dump" option.
  //

  private void dumpSegments() {

    if (dumpTriples == null) {
      return;
    }

    for (String[] triple : dumpTriples) {
      File file = new File(triple[2]);
      Integer[] bounds = MemoryDump.getSegmentBounds(triple[0], session.memory);
      int firstAddress;
      int lastAddress;
      if (bounds != null) {
        firstAddress = bounds[0];
        lastAddress = bounds[1] - MemoryState.WORD_LENGTH_BYTES;
      } else {
        try {
          var range = MemoryAddressRange.parse(triple[0]);
          firstAddress = range.first();
          lastAddress = range.last();
        } catch (IllegalArgumentException e) {
          out.println(
              "Error while attempting to save dump, segment/address-range "
                  + triple[0]
                  + " is invalid!");
          continue;
        }
      }
      DumpFormat format = DumpFormatLoader.findByCommandDescriptor(triple[1]);
      if (format == null) {
        out.println("Error while attempting to save dump, format " + triple[1] + " was not found!");
        continue;
      }
      try {
        var snapshot = format.capture(session.context(), firstAddress, lastAddress, () -> {});
        if (snapshot.size() == 0) {
          out.println("This segment has not been written to, there is nothing to dump.");
          continue;
        }
        format.write(file, snapshot);
      } catch (FileNotFoundException e) {
        out.println("Error while attempting to save dump, file " + file + " was not found!");
      } catch (AddressErrorException e) {
        out.println(
            "Error while attempting to save dump, file "
                + file
                + "!  Could not access address: "
                + e.getAddress()
                + "!");
      } catch (IOException e) {
        out.println("Error while attempting to save dump, file " + file + "!  Disk IO failed!");
      }
    }
  }

  /////////////////////////////////////////////////////////////////
  // There are no command arguments, so run in interactive mode by
  // launching the GUI-fronted integrated development environment.

  private void launchIDE() {
    // System.setProperty("apple.laf.useScreenMenuBar", "true"); // Puts MARS menu on Mac OS menu
    // bar
    new MarsSplashScreen(splashDuration).showSplash();
    SwingUtilities.invokeLater(
        () -> {
          // Turn off metal's use of bold fonts
          // UIManager.put("swing.boldMetal", Boolean.FALSE);
          new VenusUI("MARS " + ApplicationInfo.version, java.awt.Frame.NORMAL, session);
        });
  }

  //////////////////////////////////////////////////////////////////////
  // Parse command line arguments.  The initial parsing has already been
  // done, since each space-separated argument is already in a String array
  // element.  Here, we check for validity, set switch variables as appropriate
  // and build data structures.  For help option (h), display the help.
  // Returns true if command args parse OK, false otherwise.

  private boolean parseCommandArgs(String[] args) {
    if (args.length == 1 && args[0].equals("h")) {
      displayCopyright();
      displayHelp();
      return false;
    }
    boolean showCopyright = true;
    // Delay argument diagnostics until the output stream is known.
    var diagnostics = new ArrayList<String>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equalsIgnoreCase("pa")) {
        programArgumentList = new ArrayList<>(Arrays.asList(args).subList(i + 1, args.length));
        break;
      }
      if (args[i].equalsIgnoreCase("me")) {
        out = System.err;
        continue;
      }
      if (args[i].equalsIgnoreCase("nc")) {
        showCopyright = false;
        continue;
      }
      if (args[i].equalsIgnoreCase("dump")) {
        if (args.length <= i + 3) {
          diagnostics.add("Dump command line argument requires a segment, format and file name.");
          break;
        }
        if (dumpTriples == null) {
          dumpTriples = new ArrayList<>();
        }
        dumpTriples.add(new String[] {args[++i], args[++i], args[++i]});
        continue;
      }
      if (args[i].equalsIgnoreCase("mc")) {
        if (i + 1 == args.length) {
          diagnostics.add("Memory configuration option requires a configuration name.");
          continue;
        }
        String configName = args[++i];
        MemoryConfiguration config = MemoryConfigurations.getConfigurationByName(configName);
        if (config == null) {
          diagnostics.add("Invalid memory configuration: " + configName);
        } else {
          session.setMemoryConfiguration(config);
        }
        continue;
      }
      // Set MARS exit code for assemble error
      if (args[i].toLowerCase().indexOf("ae") == 0) {
        String s = args[i].substring(2);
        try {
          assembleErrorExitCode = Integer.decode(s);
          continue;
        } catch (NumberFormatException nfe) {
          // Let it fall thru and get handled by catch-all
        }
      }
      // Set MARS exit code for simulate error
      if (args[i].toLowerCase().indexOf("se") == 0) {
        String s = args[i].substring(2);
        try {
          simulateErrorExitCode = Integer.decode(s);
          continue;
        } catch (NumberFormatException nfe) {
          // Let it fall thru and get handled by catch-all
        }
      }
      if (args[i].equalsIgnoreCase("d")) {
        session.setDebug(true);
        continue;
      }
      if (args[i].equalsIgnoreCase("a")) {
        simulate = false;
        continue;
      }
      if (args[i].equalsIgnoreCase("ad") || args[i].equalsIgnoreCase("da")) {
        session.setDebug(true);
        simulate = false;
        continue;
      }
      if (args[i].equalsIgnoreCase("p")) {
        assembleProject = true;
        continue;
      }
      if (args[i].equalsIgnoreCase("dec")) {
        displayFormat = DECIMAL;
        continue;
      }
      if (args[i].equalsIgnoreCase("hex")) {
        displayFormat = HEXADECIMAL;
        continue;
      }
      if (args[i].equalsIgnoreCase("ascii")) {
        displayFormat = ASCII;
        continue;
      }
      if (args[i].equalsIgnoreCase("b")) {
        verbose = false;
        continue;
      }
      if (args[i].equalsIgnoreCase("db")) {
        delayedBranching = true;
        continue;
      }
      if (args[i].equalsIgnoreCase("np") || args[i].equalsIgnoreCase("ne")) {
        pseudo = false;
        continue;
      }
      if (args[i].equalsIgnoreCase("we")) { // added 14-July-2008 DPS
        warningsAreErrors = true;
        continue;
      }
      if (args[i].equalsIgnoreCase("sm")) { // added 17-Dec-2009 DPS
        startAtMain = true;
        continue;
      }
      if (args[i].equalsIgnoreCase("smc")) { // added 5-Jul-2013 DPS
        selfModifyingCode = true;
        continue;
      }
      if (args[i].equalsIgnoreCase("ic")) { // added 19-Jul-2012 DPS
        countInstructions = true;
        continue;
      }

      if (args[i].indexOf("$") == 0) {
        if (session.context().registerFile.getUserRegister(args[i]) == null
            && session.context().coprocessor1.getRegister(args[i]) == null) {
          diagnostics.add("Invalid Register Name: " + args[i]);
        } else {
          registerDisplayList.add(args[i]);
        }
        continue;
      }
      if (new File(args[i]).exists()) { // is it a file name?
        filenameList.add(args[i]);
        continue;
      }
      // Check for stand-alone integer, which is the max execution steps option
      try {
        Integer.decode(args[i]);
        maxSteps = Integer.decode(args[i]); // if we got here, it has to be OK
        continue;
      } catch (NumberFormatException ignored) {
      }
      // A numeric register needs the $ prefix to distinguish it from a step limit.
      if (session.context().registerFile.getUserRegister("$" + args[i]) != null
          || session.context().coprocessor1.getRegister("$" + args[i]) != null) {
        registerDisplayList.add("$" + args[i]);
        continue;
      }
      if (MemoryAddressRange.hasAddressPrefix(args[i])) {
        try {
          memoryDisplayList.add(MemoryAddressRange.parse(args[i]));
        } catch (IllegalArgumentException e) {
          diagnostics.add(e.getMessage());
        }
        continue;
      }
      diagnostics.add("Invalid Command Argument: " + args[i]);
    }
    if (showCopyright) {
      displayCopyright();
    }
    if (diagnostics.isEmpty() && filenameList.isEmpty()) {
      diagnostics.add("No assembly source files specified.");
    }
    if (!diagnostics.isEmpty()) {
      diagnostics.forEach(out::println);
      session.setExitCode(2);
      return false;
    }
    return true;
  }

  //////////////////////////////////////////////////////////////////////
  // Assemble, optionally run, and write reports and dumps for the completed phases.

  private void runCommand() {
    boolean assembled = false;
    boolean programRan = false;
    try {
      session
          .getSettings()
          .setBooleanSettingNonPersistent(Settings.DELAYED_BRANCHING_ENABLED, delayedBranching);
      session
          .getSettings()
          .setBooleanSettingNonPersistent(Settings.SELF_MODIFYING_CODE_ENABLED, selfModifyingCode);
      File mainFile =
          new File(filenameList.getFirst()).getAbsoluteFile(); // First file is "main" file
      ArrayList<String> filesToAssemble;
      if (assembleProject) {
        filesToAssemble =
            FilenameFinder.getFilenameList(mainFile.getParent(), ApplicationInfo.fileExtensions);
        if (filenameList.size() > 1) {
          // Using "p" project option PLUS listing more than one filename on command line.
          // Add the additional files, avoiding duplicates.
          filenameList.removeFirst(); // first one has already been processed
          ArrayList<String> moreFilesToAssemble =
              FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS);
          // Remove any duplicates then merge the two lists.
          for (int index2 = 0; index2 < moreFilesToAssemble.size(); index2++) {
            for (String s : filesToAssemble) {
              if (s.equals(moreFilesToAssemble.get(index2))) {
                moreFilesToAssemble.remove(index2);
                index2--; // adjust for left shift in moreFilesToAssemble...
                break; // break out of inner loop...
              }
            }
          }
          filesToAssemble.addAll(moreFilesToAssemble);
        }
      } else {
        filesToAssemble =
            FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS);
      }
      if (session.debug()) {
        out.println("--------  TOKENIZING BEGINS  -----------");
      }
      ArrayList<MIPSprogram> MIPSprogramsToAssemble =
          code.prepareFilesForAssembly(filesToAssemble, mainFile.getAbsolutePath(), null);
      if (session.debug()) {
        out.println("--------  ASSEMBLY BEGINS  -----------");
      }
      // Added logic to check for warnings and print if any. DPS 11/28/06
      ErrorList warnings = code.assemble(MIPSprogramsToAssemble, pseudo, warningsAreErrors);
      assembled = true;
      if (warnings != null && warnings.warningsOccurred()) {
        out.println(warnings.generateWarningReport());
      }
      session.initializeProgramCounter(startAtMain); // DPS 3/9/09
      if (simulate) {
        // store program args (if any) in MIPS memory
        new ProgramArgumentList(programArgumentList).storeProgramArguments(session.context());
        if (session.debug()) {
          out.println("--------  SIMULATION BEGINS  -----------");
        }
        programRan = true;
        boolean done = countInstructions
            ? code.simulate(maxSteps, completed -> instructionCount = completed)
            : code.simulate(maxSteps);
        session.setExitCode(done ? code.getExitCode() : 3);
        if (!done) {
          out.println("\nProgram terminated when maximum step limit " + maxSteps + " reached.");
        }
      }
      if (session.debug()) {
        out.println("\n--------  ALL PROCESSING COMPLETE  -----------");
      }
    } catch (ProcessingException e) {
      session.setExitCode((programRan) ? simulateErrorExitCode : assembleErrorExitCode);
      out.println(e.errors().generateErrorAndWarningReport());
      out.println("Processing terminated due to errors.");
    }
    if (programRan) {
      displayMiscellaneousPostMortem();
      displayRegistersPostMortem();
      displayMemoryPostMortem();
    }
    if (assembled) {
      dumpSegments();
    }
  }

  //////////////////////////////////////////////////////////////////////
  // Displays any specified runtime properties. Initially just instruction count
  // DPS 19 July 2012
  private void displayMiscellaneousPostMortem() {
    if (countInstructions) {
      out.println("\n" + instructionCount);
    }
  }

  //////////////////////////////////////////////////////////////////////
  // Displays requested register or registers

  private void displayRegistersPostMortem() {
    int value; // handy local to use throughout the next couple loops
    String strValue;
    // Display requested register contents
    out.println();
    for (String reg : registerDisplayList) {
      if (session.context().registerFile.getUserRegister(reg) != null) {
        // integer register
        if (verbose) {
          out.print(reg + "\t");
        }
        value = session.context().registerFile.getUserRegister(reg).getValue();
        out.println(formatIntForDisplay(value));
      } else {
        // floating point register
        float fvalue = session.context().coprocessor1.getFloatFromRegister(reg);
        int ivalue = session.context().coprocessor1.getIntFromRegister(reg);
        double dvalue = Double.NaN;
        long lvalue = 0;
        boolean hasDouble = false;
        try {
          dvalue = session.context().coprocessor1.getDoubleFromRegisterPair(reg);
          lvalue = session.context().coprocessor1.getLongFromRegisterPair(reg);
          hasDouble = true;
        } catch (InvalidRegisterAccessException ignored) {
        }
        if (verbose) {
          out.print(reg + "\t");
        }
        if (displayFormat == HEXADECIMAL) {
          // display float (and double, if applicable) in hex
          out.print(Binary.binaryStringToHexString(Binary.intToBinaryString(ivalue)));
          if (hasDouble) {
            out.println("\t" + Binary.binaryStringToHexString(Binary.longToBinaryString(lvalue)));
          } else {
            out.println();
          }
        } else if (displayFormat == DECIMAL) {
          // display float (and double, if applicable) in decimal
          out.print(fvalue);
          if (hasDouble) {
            out.println("\t" + dvalue);
          } else {
            out.println();
          }
        } else { // displayFormat == ASCII
          out.print(Binary.intToAscii(ivalue));
          if (hasDouble) {
            out.println(
                "\t"
                    + Binary.intToAscii(Binary.highOrderLongToInt(lvalue))
                    + Binary.intToAscii(Binary.lowOrderLongToInt(lvalue)));
          } else {
            out.println();
          }
        }
      }
    }
  }

  //////////////////////////////////////////////////////////////////////
  // Formats int value for display: decimal, hex, ascii
  private String formatIntForDisplay(int value) {
    return switch (displayFormat) {
      case DECIMAL -> "" + value;
      case ASCII -> Binary.intToAscii(value);
      default -> Binary.intToHexString(value);
    };
  }

  //////////////////////////////////////////////////////////////////////
  // Displays requested memory range or ranges

  private void displayMemoryPostMortem() {
    for (var range : memoryDisplayList) {
      long first = Integer.toUnsignedLong(range.first());
      long last = Integer.toUnsignedLong(range.last());
      int valuesDisplayed = 0;
      for (long address = first; address <= last; address += MemoryState.WORD_LENGTH_BYTES) {
        int addr = (int) address;
        if (valuesDisplayed % memoryWordsPerLine == 0) {
          out.print((valuesDisplayed > 0) ? "\n" : "");
          if (verbose) {
            out.print("Mem[" + Binary.intToHexString(addr) + "]\t");
          }
        }
        try {
          int value;
          // Allow display of binary text segment (machine code) DPS 14-July-2008
          if (session.memory.inTextSegment(addr) || session.memory.inKernelTextSegment(addr)) {
            Integer iValue = session.memory.getRawWordOrNull(addr);
            value = (iValue == null) ? 0 : iValue;
          } else {
            value = session.memory.getWordNoNotify(addr);
          }
          out.print(formatIntForDisplay(value) + "\t");
        } catch (AddressErrorException aee) {
          out.print("Invalid address: " + addr + "\t");
        }
        valuesDisplayed++;
      }
      out.println();
    }
  }

  private void displayCopyright() {
    out.println(
        "MARS "
            + ApplicationInfo.version
            + "  Copyright "
            + ApplicationInfo.copyrightYears
            + " "
            + ApplicationInfo.copyrightHolders
            + "\n");
  }

  ///////////////////////////////////////////////////////////////////////
  //  Display command line help text

  private void displayHelp() {
    String[] segmentNames = MemoryDump.getSegmentNames();
    StringBuilder segments = new StringBuilder();
    for (int i = 0; i < segmentNames.length; i++) {
      segments.append(segmentNames[i]);
      if (i < segmentNames.length - 1) {
        segments.append(", ");
      }
    }
    List<DumpFormat> dumpFormats = DumpFormatLoader.FORMATS;
    StringBuilder formats = new StringBuilder();
    for (int i = 0; i < dumpFormats.size(); i++) {
      formats.append(dumpFormats.get(i).getCommandDescriptor());
      if (i < dumpFormats.size() - 1) {
        formats.append(", ");
      }
    }
    out.println("Usage:  Mars  [options] filename [additional filenames]");
    out.println("  Valid options (not case sensitive, separate by spaces) are:");
    out.println("      a  -- assemble only, do not simulate");
    out.println(
        "  ae<n>  -- terminate MARS with integer exit code <n> if an assemble error occurs.");
    out.println("  ascii  -- display memory or register contents interpreted as ASCII codes.");
    out.println("      b  -- brief - do not display register/memory address along with contents");
    out.println("      d  -- display MARS debugging statements");
    out.println("     db  -- MIPS delayed branching is enabled");
    out.println("    dec  -- display memory or register contents in decimal.");
    out.println("   dump <segment> <format> <file> -- memory dump of specified memory segment");
    out.println("            in specified format to specified file.  Option may be repeated.");
    out.println("            Dump occurs at the end of simulation unless 'a' option is used.");
    out.println("            Segment and format are case-sensitive and possible values are:");
    out.println("            <segment> = " + segments);
    out.println("            <format> = " + formats);
    out.println("      h  -- display this help.  Use by itself with no filename.");
    out.println("    hex  -- display memory or register contents in hexadecimal (default)");
    out.println("     ic  -- display count of MIPS basic instructions 'executed'");
    out.println("     mc <config>  -- set memory configuration.  Argument <config> is");
    out.println("            case-sensitive and possible values are: Default for the default");
    out.println("            32-bit address space, CompactDataAtZero for a 32KB memory with");
    out.println("            data segment at address 0, or CompactTextAtZero for a 32KB");
    out.println("            memory with text segment at address 0.");
    out.println("     me  -- display MARS messages to standard err instead of standard out. ");
    out.println("            Can separate messages from program output using redirection");
    out.println(
        "     nc  -- do not display copyright notice (for cleaner redirected/piped output).");
    out.println("     np  -- use of pseudo instructions and formats not permitted");
    out.println(
        "      p  -- Project mode - assemble all files in the same directory as given file.");
    out.println(
        "  se<n>  -- terminate MARS with integer exit code <n> if a simulation (run) error"
            + " occurs.");
    out.println("     sm  -- start execution at statement with global label main, if defined");
    out.println(
        "    smc  -- Self Modifying Code - Program can write and branch to either text or data"
            + " segment");
    out.println("    <n>  -- where <n> is an integer maximum count of steps to simulate.");
    out.println("            If 0, negative or not specified, there is no maximum.");
    out.println(" $<reg>  -- where <reg> is number or name (e.g. 5, t3, f10) of register whose ");
    out.println("            content to display at end of run.  Option may be repeated.");
    out.println("<reg_name>  -- where <reg_name> is name (e.g. t3, f10) of register whose");
    out.println("            content to display at end of run.  Option may be repeated. ");
    out.println("            The $ is not required.");
    out.println("<m>-<n>  -- memory address range from <m> to <n> whose contents to");
    out.println("            display at end of run. <m> and <n> may be hex or decimal,");
    out.println("            must be on word boundary, <m> <= <n>.  Option may be repeated.");
    out.println("     pa  -- Program Arguments follow in a space-separated list.  This");
    out.println("            option must be placed AFTER ALL FILE NAMES, because everything");
    out.println("            that follows it is interpreted as a program argument to be");
    out.println("            made available to the MIPS program at runtime.");
    out.println("If more than one filename is listed, the first is assumed to be the main");
    out.println("unless the global statement label 'main' is defined in one of the files.");
    out.println("Exception handler not automatically assembled.  Add it to the file list.");
    out.println("Options used here do not affect MARS Settings menu values and vice versa.");
    out.println("Exit status defaults: 1 for assembly or simulation errors, 2 for invalid");
    out.println("arguments, and 3 when execution reaches the step limit.");
  }
}
