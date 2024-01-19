// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsDirectiveMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsDirectiveStatementImpl extends MipsDirectiveMixin implements MipsDirectiveStatement {

  public MipsDirectiveStatementImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitDirectiveStatement(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MipsVisitor) accept((MipsVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @NotNull
  public List<MipsDirectiveArg> getDirectiveArgList() {
    return PsiTreeUtil.getChildrenOfTypeAsList(this, MipsDirectiveArg.class);
  }

}
