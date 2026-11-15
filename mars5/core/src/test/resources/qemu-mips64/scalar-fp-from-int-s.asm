# Round large signed integer operands and check the Inexact flag.
lui $8, 0x8000
ori $8, $8, 0x0001
mtc1 $8, $f10
ori $8, $0, 0x8000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
dmtc1 $8, $f11
ori $11, $0, 2
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $11, $31
cvt.s.w $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $11, $31
cvt.s.l $f9, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
