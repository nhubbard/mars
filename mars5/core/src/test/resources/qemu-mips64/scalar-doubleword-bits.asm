# Distinct byte and halfword order, including gaps between bit fields.
ld $8, 0($4)
ld $9, 8($4)
dbitswap $10, $8
sd $10, 0($5)
dsbh $10, $8
sd $10, 8($5)
dshd $10, $8
sd $10, 16($5)
dclo $10, $9
sd $10, 24($5)
dclz $10, $8
sd $10, 32($5)
dclz $10, $0
sd $10, 40($5)
dalign $10, $8, $9, 0
sd $10, 48($5)
dalign $10, $8, $9, 7
sd $10, 56($5)
dext $10, $8, 31, 32
sd $10, 64($5)
dextu $10, $8, 63, 1
sd $10, 72($5)
dextm $10, $8, 0, 64
sd $10, 80($5)
or $10, $9, $0
dins $10, $8, 0, 32
sd $10, 88($5)
or $10, $9, $0
dinsu $10, $8, 63, 1
sd $10, 96($5)
or $10, $9, $0
dinsm $10, $8, 31, 33
sd $10, 104($5)
or $10, $9, $0
dinsm $10, $8, 0, 64
sd $10, 112($5)
