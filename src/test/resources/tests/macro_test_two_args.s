.macro two_args (%x, %y)
li $v0, %x
li $a0, %y
syscall
.end_macro

two_args (1, 1)