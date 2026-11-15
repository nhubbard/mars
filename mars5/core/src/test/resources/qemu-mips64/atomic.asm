# Each SC follows a matching LL without intervening memory access.
ll $8, 0($4)
addiu $8, $8, 1
sc $8, 0($4)
lw $9, 0($4)
lld $10, 8($4)
daddiu $10, $10, 1
scd $10, 8($4)
ld $11, 8($4)
sd $8, 0($5)
sd $9, 8($5)
sd $10, 16($5)
sd $11, 24($5)
