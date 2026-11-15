# Compare ordered values.
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f10
ori $8, $0, 0x4000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f11
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
cmp.af.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
cmp.un.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
cmp.eq.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
cmp.ueq.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 24($5)
sw $9, 108($5)
ctc1 $0, $31
cmp.lt.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 32($5)
sw $9, 112($5)
ctc1 $0, $31
cmp.ult.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 40($5)
sw $9, 116($5)
ctc1 $0, $31
cmp.le.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 48($5)
sw $9, 120($5)
ctc1 $0, $31
cmp.ule.d $f9, $f10, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 56($5)
sw $9, 124($5)
