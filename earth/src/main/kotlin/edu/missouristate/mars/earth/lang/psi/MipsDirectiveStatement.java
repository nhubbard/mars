// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface MipsDirectiveStatement extends MipsNamedElement {

  @NotNull
  List<MipsDirectiveArg> getDirectiveArgList();

  //WARNING: getName(...) is skipped
  //matching getName(MipsDirectiveStatement, ...)
  //methods are not found in null

  //WARNING: setName(...) is skipped
  //matching setName(MipsDirectiveStatement, ...)
  //methods are not found in null

  @Nullable
  List<MipsNumberLiteral> getNumbers();

  @Nullable
  String getIdentifier();

  @Nullable
  String getString();

}
