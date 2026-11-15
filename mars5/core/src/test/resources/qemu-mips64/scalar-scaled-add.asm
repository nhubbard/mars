# LSA produces a sign-extended word. DLSA keeps the full carry.
lui $8, 0x8000
daddiu $9, $0, -1
lsa $10, $8, $9, 1
dlsa $11, $8, $9, 1
lsa $12, $8, $9, 4
dlsa $13, $8, $9, 4
sd $10, 0($5)
sd $11, 8($5)
sd $12, 16($5)
sd $13, 24($5)
