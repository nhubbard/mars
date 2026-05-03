package edu.missouristate.mars.mips.dump;

import java.util.List;

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

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

/** Registry of all available memory dump formats. */
public final class DumpFormatLoader {

  /** All concrete {@link DumpFormat} implementations available in this build. */
  public static final List<DumpFormat> FORMATS =
      List.of(
          new AsciiTextDumpFormat(),
          new BinaryDumpFormat(),
          new BinaryTextDumpFormat(),
          new HexTextDumpFormat(),
          new IntelHexDumpFormat(),
          new SegmentWindowDumpFormat());

  /**
   * Returns the dump format whose command-line descriptor matches the given string, or {@code null}
   * if none matches.
   */
  public static DumpFormat findByCommandDescriptor(String descriptor) {
    return FORMATS.stream()
        .filter(f -> f.getCommandDescriptor().equals(descriptor))
        .findFirst()
        .orElse(null);
  }

  private DumpFormatLoader() {}
}
