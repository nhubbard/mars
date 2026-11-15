# JIALC links before the fetch fault and never executes the next instruction.
addiupc $4, 0
daddiu $12, $0, 27
jialc $4, 2
daddiu $12, $12, 1
