# Sign manipulation and plain moves preserve the signaling NaN payload.
lui $8, 0xff80
ori $8, $8, 0x0123
mtc1 $8, $f10
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
mov.s $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
abs.s $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
neg.s $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
class.s $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 24($5)
sw $9, 108($5)
