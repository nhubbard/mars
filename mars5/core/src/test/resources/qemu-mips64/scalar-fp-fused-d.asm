# The product alone rounds to one. Fused cancellation retains the low bits.
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
dmtc1 $8, $f10
ori $8, $0, 0x3fef
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xfffe
dmtc1 $8, $f11
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f12
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f13
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
mov.d $f9, $f12
maddf.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
mov.d $f9, $f13
msubf.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 8($5)
sw $9, 100($5)
