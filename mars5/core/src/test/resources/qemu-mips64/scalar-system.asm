# Interrupt control changes IE and optionally returns the previous Status.
di
ei $8
di $9
andi $8, $8, 1
andi $9, $9, 1
sd $8, 0($5)
sd $9, 8($5)
ei
di
mfc0 $9, $12, 0
andi $9, $9, 1
sd $9, 16($5)

# Word and doubleword moves through EPC use their respective widths.
lui $8, 0x8123
ori $8, $8, 0x4560
mtc0 $8, $14
mfc0 $9, $14
sd $9, 24($5)
ori $10, $0, 1
dsll32 $10, $10, 0
daddiu $10, $10, 0x2340
dmtc0 $10, $14, 0
dmfc0 $9, $14
sd $9, 32($5)
mfc0 $9, $14
sd $9, 40($5)
