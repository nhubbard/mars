.macro thing
li $v0, 1
li $a0, 1
syscall
.end_macro

thing_2

.macro thing_2
li $v0, 1
li $a0, 2
syscall
.end_macro