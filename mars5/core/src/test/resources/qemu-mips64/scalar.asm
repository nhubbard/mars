# The harness passes input in $4 and output in $5.
ld $8, 0($4)
ld $9, 8($4)
daddu $10, $8, $9
dsubu $11, $8, $9
drotr $12, $8, 17
addu $13, $8, $9
dlsa $14, $8, $9, 3
dmuhu $15, $8, $9
sd $10, 0($5)
sd $11, 8($5)
sd $12, 16($5)
sd $13, 24($5)
sd $14, 32($5)
sd $15, 40($5)
lw $8, 1($4)
lh $9, 17($4)
ld $10, 5($4)
sd $8, 48($5)
sd $9, 56($5)
sd $10, 64($5)
sw $8, 73($5)
sh $9, 81($5)
sd $10, 89($5)
