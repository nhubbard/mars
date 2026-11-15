# Compare signed and unsigned high halves of the same products.
lui $8, 0x8000
daddiu $9, $0, -3
mul $10, $8, $9
sd $10, 0($5)
mulu $10, $8, $9
sd $10, 8($5)
muh $10, $8, $9
sd $10, 16($5)
muhu $10, $8, $9
sd $10, 24($5)
dmul $10, $8, $9
sd $10, 32($5)
dmulu $10, $8, $9
sd $10, 40($5)
dmuh $10, $8, $9
sd $10, 48($5)
dmuhu $10, $8, $9
sd $10, 56($5)
ld $8, 0($4)
ld $9, 8($4)
dmuh $10, $8, $9
sd $10, 64($5)
dmuhu $10, $8, $9
sd $10, 72($5)
