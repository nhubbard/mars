# The product alone rounds to one. Fused cancellation retains the low bits.
lui $8, 0x3f80
ori $8, $8, 0x0001
mtc1 $8, $f10
lui $8, 0x3f7f
ori $8, $8, 0xfffe
mtc1 $8, $f11
lui $8, 0xbf80
mtc1 $8, $f12
lui $8, 0x3f80
mtc1 $8, $f13
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
mov.s $f9, $f12
maddf.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
mov.s $f9, $f13
msubf.s $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
