# Record displacements from a common PC anchor, on both sides of it.
addiupc $8, 0
addiupc $9, -1048576
addiupc $10, 1048572
auipc $11, -32768
auipc $12, 32767
aluipc $13, -1
aluipc $14, 1
dsubu $9, $9, $8
dsubu $10, $10, $8
dsubu $11, $11, $8
dsubu $12, $12, $8
dsrl $8, $8, 16
dsll $8, $8, 16
dsubu $13, $13, $8
dsubu $14, $14, $8
sd $9, 0($5)
sd $10, 8($5)
sd $11, 16($5)
sd $12, 24($5)
sd $13, 32($5)
sd $14, 40($5)
