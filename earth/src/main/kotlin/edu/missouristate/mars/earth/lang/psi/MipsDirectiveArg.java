// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface MipsDirectiveArg extends MipsNamedElement {

  @NotNull
  List<MipsNumberLiteral> getNumberLiteralList();

  @Nullable
  MipsNumberRange getNumberRange();

  @Nullable
  MipsStringLiteral getStringLiteral();

  boolean isNumber();

  @Nullable
  MipsNumberLiteral getNumber();

  boolean isIdentifier();

  boolean isString();

}
