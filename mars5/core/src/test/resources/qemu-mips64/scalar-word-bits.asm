# Use sign-extended inputs with different bits in every byte.
lui $8, 0x81f0
ori $8, $8, 0x5a3c
lui $9, 0xec12
ori $9, $9, 0xa984
bitswap $10, $8
sd $10, 0($5)
wsbh $10, $8
sd $10, 8($5)
seb $10, $9
sd $10, 16($5)
seh $10, $9
sd $10, 24($5)
clo $10, $9
sd $10, 32($5)
clz $10, $8
sd $10, 40($5)
clz $10, $0
sd $10, 48($5)
align $10, $8, $9, 0
sd $10, 56($5)
align $10, $8, $9, 3
sd $10, 64($5)
ext $10, $8, 0, 32
sd $10, 72($5)
or $10, $9, $0
ins $10, $8, 29, 3
sd $10, 80($5)
ins $8, $8, 0, 32
sd $8, 88($5)
