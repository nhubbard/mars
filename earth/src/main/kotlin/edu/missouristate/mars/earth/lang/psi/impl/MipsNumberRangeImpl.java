// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsNumberRangeMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsNumberRangeImpl extends MipsNumberRangeMixin implements MipsNumberRange {

  public MipsNumberRangeImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitNumberRange(this);
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

}
