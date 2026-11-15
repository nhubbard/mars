# Both conditions inspect the full 64-bit register, even with a zero word.
ld $8, 0($4)
ori $9, $0, 1
dsll32 $9, $9, 0
seleqz $10, $8, $0
seleqz $11, $8, $9
selnez $12, $8, $0
selnez $13, $8, $9
sd $10, 0($5)
sd $11, 8($5)
sd $12, 16($5)
sd $13, 24($5)
