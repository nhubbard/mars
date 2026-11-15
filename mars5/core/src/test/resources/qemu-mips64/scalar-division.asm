# Three-register word and doubleword division. Word results are sign-extended.
daddiu $8, $0, -7
daddiu $9, $0, 3
daddiu $10, $0, -1
daddiu $11, $0, 1
dsll32 $11, $11, 31
div $12, $8, $9
mod $13, $8, $9
divu $14, $10, $9
modu $15, $10, $9
sd $12, 0($5)
sd $13, 8($5)
sd $14, 16($5)
sd $15, 24($5)
ddiv $12, $11, $9
dmod $13, $11, $9
ddivu $14, $10, $9
dmodu $15, $10, $9
sd $12, 32($5)
sd $13, 40($5)
sd $14, 48($5)
sd $15, 56($5)
