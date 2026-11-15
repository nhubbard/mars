# Fixed rounding instructions ignore FCSR rounding direction. CVT uses it.
ori $8, $0, 0xbff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f10
ori $11, $0, 2
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $11, $31
ceil.w.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $11, $31
floor.w.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $11, $31
round.w.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $11, $31
trunc.w.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 24($5)
sw $9, 108($5)
ctc1 $11, $31
cvt.w.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
swc1 $f9, 32($5)
sw $9, 112($5)
ctc1 $11, $31
ceil.l.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 40($5)
sw $9, 116($5)
ctc1 $11, $31
floor.l.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 48($5)
sw $9, 120($5)
ctc1 $11, $31
round.l.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 56($5)
sw $9, 124($5)
ctc1 $11, $31
trunc.l.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 64($5)
sw $9, 128($5)
ctc1 $11, $31
cvt.l.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 72($5)
sw $9, 132($5)
