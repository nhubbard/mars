# Compare a quiet NaN with a numeric operand.
lui $8, 0x7fc0
ori $8, $8, 0x0123
mtc1 $8, $f10
lui $8, 0x4000
mtc1 $8, $f11
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
cmp.or.s $f9, $f10, $f11 # llvm-word 0x468b5251
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
cmp.une.s $f9, $f10, $f11 # llvm-word 0x468b5252
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
cmp.ne.s $f9, $f10, $f11 # llvm-word 0x468b5253
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
cmp.sor.s $f9, $f10, $f11 # llvm-word 0x468b5259
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 24($5)
sw $9, 108($5)
ctc1 $0, $31
cmp.sune.s $f9, $f10, $f11 # llvm-word 0x468b525a
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 32($5)
sw $9, 112($5)
ctc1 $0, $31
cmp.sne.s $f9, $f10, $f11 # llvm-word 0x468b525b
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 40($5)
sw $9, 116($5)
