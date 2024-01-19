// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsNumberLiteralMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsNumberLiteralImpl extends MipsNumberLiteralMixin implements MipsNumberLiteral {

  public MipsNumberLiteralImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitNumberLiteral(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MipsVisitor) accept((MipsVisitor)visitor);
    else super.accept(visitor);
  }

}
