// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface MipsInstructionArg extends MipsNamedElement {

  @Nullable
  MipsNumberLiteral getNumberLiteral();

  @Nullable
  MipsRegisterLiteral getRegisterLiteral();

  boolean isRegister();

  boolean isNumber();

  boolean isIdentifier();

}
