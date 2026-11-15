# Compare a quiet NaN with a numeric operand.
lui $8, 0x7fc0
ori $8, $8, 0x0123
mtc1 $8, $f10
lui $8, 0x4000
mtc1 $8, $f11
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
cmp.saf.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
cmp.sun.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
cmp.seq.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
cmp.sueq.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 24($5)
sw $9, 108($5)
ctc1 $0, $31
cmp.slt.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 32($5)
sw $9, 112($5)
ctc1 $0, $31
cmp.sult.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 40($5)
sw $9, 116($5)
ctc1 $0, $31
cmp.sle.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 48($5)
sw $9, 120($5)
ctc1 $0, $31
cmp.sule.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 56($5)
sw $9, 124($5)
