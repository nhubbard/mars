# Use exact reciprocal results and capture the flags for square root and RINT.
ori $8, $0, 0x4010
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f10
ori $8, $0, 0xbff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
dmtc1 $8, $f11
lui $10, 0x0003
ori $10, $10, 0xffff
ctc1 $0, $31
sqrt.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 0($5)
sw $9, 96($5)
ctc1 $0, $31
recip.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 8($5)
sw $9, 100($5)
ctc1 $0, $31
rsqrt.d $f9, $f10
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 16($5)
sw $9, 104($5)
ctc1 $0, $31
rint.d $f9, $f11
cfc1 $9, $31
and $9, $9, $10
sdc1 $f9, 24($5)
sw $9, 108($5)
