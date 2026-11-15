# Select by raw low bit, including when the destination supplies that bit.
ori $8, $0, 0x7ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0123
dmtc1 $8, $f10
ori $8, $0, 0x8000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f11
dmtc1 $0, $f12
ori $8, $0, 0x0000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
dmtc1 $8, $f13
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
mov.d $f9, $f12
sel.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
mov.d $f9, $f13
sel.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
seleqz.d $f9, $f10, $f12
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
seleqz.d $f9, $f10, $f13
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 24($5)
sw $9, 108($5)
ctc1 $0, $31
selnez.d $f9, $f10, $f12
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 32($5)
sw $9, 112($5)
ctc1 $0, $31
selnez.d $f9, $f10, $f13
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 40($5)
sw $9, 116($5)
