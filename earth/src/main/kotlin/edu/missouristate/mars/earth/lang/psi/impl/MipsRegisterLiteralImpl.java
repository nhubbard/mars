// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsRegisterLiteralMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsRegisterLiteralImpl extends MipsRegisterLiteralMixin implements MipsRegisterLiteral {

  public MipsRegisterLiteralImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitRegisterLiteral(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MipsVisitor) accept((MipsVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public MipsRegisterOffset getRegisterOffset() {
    return findChildByClass(MipsRegisterOffset.class);
  }

}
