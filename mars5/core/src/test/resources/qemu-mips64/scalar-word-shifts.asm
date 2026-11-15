# Word shifts and rotations have five-bit counts and sign-extend results.
lui $8, 0x8001
ori $8, $8, 3
daddiu $9, $0, 63
sll $10, $8, 0
sd $10, 0($5)
sll $10, $8, 31
sd $10, 8($5)
sra $10, $8, 31
sd $10, 16($5)
srl $10, $8, 31
sd $10, 24($5)
rotr $10, $8, 31
sd $10, 32($5)
sllv $10, $8, $9
sd $10, 40($5)
srav $10, $8, $9
sd $10, 48($5)
srlv $10, $8, $9
sd $10, 56($5)
rotrv $10, $8, $9
sd $10, 64($5)
