# Divide an inexact quotient under upward rounding.
lui $8, 0x4040
mtc1 $8, $f10
lui $8, 0x40e0
mtc1 $8, $f11
ori $11, $0, 2
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $11, $31
add.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $11, $31
sub.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $11, $31
mul.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $11, $31
div.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 24($5)
sw $9, 108($5)
