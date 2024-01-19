// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface MipsInstruction extends MipsNamedElement {

  @NotNull
  List<MipsInstructionArg> getInstructionArgList();

  @Nullable
  PsiElement getOperator();

  @NotNull
  List<MipsInstructionArg> getArguments();

  boolean hasArguments();

}
