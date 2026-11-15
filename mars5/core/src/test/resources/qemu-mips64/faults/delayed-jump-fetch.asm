# The delay slot executes and the return address is linked before fetch faults.
addiupc $4, 0
daddiu $8, $4, 2
jalr $8
daddiu $12, $0, 27
