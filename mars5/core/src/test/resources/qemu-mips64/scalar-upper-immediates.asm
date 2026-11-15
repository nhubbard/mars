# Add signed immediates to four upper register positions.
lui $8, 0x7fff
ori $8, $8, 0xffff
aui $10, $8, 1
sd $10, 0($5)
daui $10, $8, 1
sd $10, 8($5)
aui $10, $8, 0x8000
sd $10, 16($5)
daui $10, $8, 0x8000
sd $10, 24($5)
or $10, $8, $0
dahi $10, $10, 0x8000
sd $10, 32($5)
or $10, $8, $0
dati $10, $10, 0x8000
sd $10, 40($5)
