# The cause/flag and enable/rounding views update different FCSR fields.
ctc1 $0, $31
ori $8, $0, 0x0044
ctc1 $8, $26
ori $8, $0, 0x0103
ctc1 $8, $28
cfc1 $9, $26
sw $9, 0($5)
cfc1 $9, $28
sw $9, 4($5)
cfc1 $9, $31
andi $9, $9, 0x0fff
sw $9, 8($5)
