# Exercise both immediate encodings and wrap oversized register counts.
ld $8, 0($4)
ld $11, 8($4)
daddiu $9, $0, 127
dsll $10, $8, 31
sd $10, 0($5)
dsll32 $10, $8, 31
sd $10, 8($5)
dsra $10, $11, 31
sd $10, 16($5)
dsra32 $10, $11, 31
sd $10, 24($5)
dsrl $10, $8, 31
sd $10, 32($5)
dsrl32 $10, $8, 31
sd $10, 40($5)
drotr $10, $8, 31
sd $10, 48($5)
drotr32 $10, $8, 31
sd $10, 56($5)
dsllv $10, $8, $9
sd $10, 64($5)
dsrav $10, $11, $9
sd $10, 72($5)
dsrlv $10, $8, $9
sd $10, 80($5)
drotrv $10, $8, $9
sd $10, 88($5)
