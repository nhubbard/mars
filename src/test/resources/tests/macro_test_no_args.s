.macro print_one
li $v0, 1
li $a0, 1
syscall
.end_macro

print_one