# Trap between the linked load and store, then observe the return policy.
ll $9, 0($4)
break 9
addiu $9, $9, 1
sc $9, 0($4)
sd $9, 0($5)
lw $10, 0($4)
sd $10, 8($5)
bc finished
nop

.ktext 0x80000180
dmfc0 $8, $14
daddiu $8, $8, 4
dmtc0 $8, $14
eretnc
nop

.text
finished:
nop
