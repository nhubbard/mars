# Branch on the low FPR bit. Taken and untaken forms both execute their slots.
daddiu $8, $0, 2
daddiu $9, $0, 1
mtc1 $8, $f0
mtc1 $9, $f1
daddiu $12, $0, 0
bc1eqz $f0, even_taken
daddiu $12, $12, 1
daddiu $12, $12, 2
even_taken:
bc1eqz $f1, even_untaken
daddiu $12, $12, 4
daddiu $12, $12, 8
even_untaken:
bc1nez $f1, odd_taken
daddiu $12, $12, 16
daddiu $12, $12, 32
odd_taken:
bc1nez $f0, odd_untaken
daddiu $12, $12, 64
daddiu $12, $12, 128
odd_untaken:
sd $12, 0($5)
