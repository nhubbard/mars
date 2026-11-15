# Compare full-width arithmetic with sign-extended word results.
lui $8, 0x4000
daddiu $9, $0, -3
add $10, $8, $9
sd $10, 0($5)
addu $10, $8, $8
sd $10, 8($5)
sub $10, $8, $9
sd $10, 16($5)
subu $10, $9, $8
sd $10, 24($5)
addiu $10, $8, -32768
sd $10, 32($5)
dadd $10, $8, $9
sd $10, 40($5)
dsub $10, $8, $9
sd $10, 48($5)
slt $10, $9, $8
sd $10, 56($5)
sltu $10, $9, $8
sd $10, 64($5)
slti $10, $9, -2
sd $10, 72($5)
sltiu $10, $9, -2
sd $10, 80($5)
and $10, $8, $9
sd $10, 88($5)
or $10, $8, $9
sd $10, 96($5)
nor $10, $8, $9
sd $10, 104($5)
xor $10, $8, $9
sd $10, 112($5)
andi $10, $9, 0xf0f0
sd $10, 120($5)
ori $10, $9, 0xfffe
sd $10, 128($5)
xori $10, $9, 0x8000
sd $10, 136($5)
