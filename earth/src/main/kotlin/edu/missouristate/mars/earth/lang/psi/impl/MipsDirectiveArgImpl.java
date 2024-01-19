// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsDirectiveArgMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsDirectiveArgImpl extends MipsDirectiveArgMixin implements MipsDirectiveArg {

  public MipsDirectiveArgImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitDirectiveArg(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MipsVisitor) accept((MipsVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<MipsNumberLiteral> getNumberLiteralList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MipsNumberLiteral.class);
  }

  @Override
  @Nullable
  public MipsNumberRange getNumberRange() {
    return findChildByClass(MipsNumberRange.class);
  }

  @Override
  @Nullable
  public MipsStringLiteral getStringLiteral() {
    return findChildByClass(MipsStringLiteral.class);
  }

}
