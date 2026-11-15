# Compact taken and untaken branches, with a legacy delay slot in the same program.
daddiu $8, $0, 1
daddiu $9, $0, 2
daddiu $10, $0, 0
beqc $8, $9, compact_exit
daddiu $10, $10, 1
bnec $8, $9, compact_taken
daddiu $10, $10, 2
compact_taken:
beq $8, $8, delayed_taken
daddiu $10, $10, 4
daddiu $10, $10, 8
delayed_taken:
balc compact_call
daddiu $10, $10, 16
bc compact_exit
nop
compact_call:
daddiu $10, $10, 32
jic $31, 0
daddiu $10, $10, 64
compact_exit:
sd $10, 0($5)
