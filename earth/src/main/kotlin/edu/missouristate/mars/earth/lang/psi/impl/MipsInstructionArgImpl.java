// This is a generated file. Not intended for manual editing.
package edu.missouristate.mars.earth.lang.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static edu.missouristate.mars.earth.lang.psi.MipsElementTypes.*;
import edu.missouristate.mars.earth.lang.psi.mixins.MipsInstructionArgMixin;
import edu.missouristate.mars.earth.lang.psi.*;

public class MipsInstructionArgImpl extends MipsInstructionArgMixin implements MipsInstructionArg {

  public MipsInstructionArgImpl(@NotNull ASTNode node) {
    super(node);
  }

  public void accept(@NotNull MipsVisitor visitor) {
    visitor.visitInstructionArg(this);
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof MipsVisitor) accept((MipsVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public MipsNumberLiteral getNumberLiteral() {
    return findChildByClass(MipsNumberLiteral.class);
  }

  @Override
  @Nullable
  public MipsRegisterLiteral getRegisterLiteral() {
    return findChildByClass(MipsRegisterLiteral.class);
  }

}
