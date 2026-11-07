package edu.missouristate.mars.simulator;

/*
Copyright (c) 2003-2007,  Pete Sanderson and Kenneth Vollmar

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

/** Legacy process-global branch state used by the existing simulator. */
public class DelayedBranch {
  private static final DelayedBranchState legacy = new DelayedBranchState();

  /** Transitional access for the legacy instruction execution context. */
  public static DelayedBranchState legacyState() {
    return legacy;
  }

  public static void register(int targetAddress) {
    legacy.register(targetAddress);
  }

  static void trigger() {
    legacy.trigger();
  }

  static void clear() {
    legacy.clear();
  }

  static boolean isRegistered() {
    return legacy.isRegistered();
  }

  static boolean isTriggered() {
    return legacy.isTriggered();
  }

  static int getBranchTargetAddress() {
    return legacy.getBranchTargetAddress();
  }
}
