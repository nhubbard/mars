# Load negative byte, halfword, and word values with both extensions.
lui $8, 0x81a2
ori $8, $8, 0xb3c4
sb $8, 0($4)
sh $8, 2($4)
sw $8, 4($4)
lb $9, 0($4)
lbu $10, 0($4)
lh $11, 2($4)
lhu $12, 2($4)
lw $13, 4($4)
lwu $14, 4($4)
sd $9, 0($5)
sd $10, 8($5)
sd $11, 16($5)
sd $12, 24($5)
sd $13, 32($5)
sd $14, 40($5)
