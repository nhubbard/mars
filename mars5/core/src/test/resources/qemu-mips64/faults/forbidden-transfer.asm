# An untaken compact branch attempts a forbidden control transfer.
daddiu $8, $0, 1
daddiu $9, $0, 2
daddiu $12, $0, 27
beqc $8, $9, not_reached
beq $8, $8, not_reached
daddiu $12, $12, 1
not_reached:
nop
