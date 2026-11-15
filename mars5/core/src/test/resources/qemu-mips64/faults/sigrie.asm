# SIGRIE raises Reserved Instruction regardless of its code.
daddiu $12, $0, 17
sigrie 7
nop
