# A branch in a delay slot raises Reserved Instruction before it transfers.
daddiu $12, $0, 27
beq $0, $0, not_reached
bc not_reached
daddiu $12, $12, 1
not_reached:
nop
