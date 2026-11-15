# Round 1.5 and -1.5 toward positive infinity, then compare and classify them.
lui $8, 0x3fc0
mtc1 $8, $f0
lui $8, 0xbfc0
mtc1 $8, $f1
ori $8, $0, 2
ctc1 $8, $31
rint.s $f2, $f0
rint.s $f3, $f1
cfc1 $9, $31
andi $9, $9, 0x007f
cmp.lt.s $f4, $f1, $f0
class.s $f5, $f1
mfc1 $10, $f5
swc1 $f2, 0($5)
swc1 $f3, 4($5)
swc1 $f4, 8($5)
sw $10, 12($5)
sw $9, 16($5)
