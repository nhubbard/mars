# Enabled division by zero preserves the old destination and sticky flag.
lui $8, 0x3f80
mtc1 $8, $f10
mtc1 $0, $f11
ld $8, 0($4)
dmtc1 $8, $f9
ori $8, $0, 0x0408
ctc1 $8, $31
div.s $f9, $f10, $f11
