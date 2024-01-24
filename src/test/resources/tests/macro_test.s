.macro print_int (%x)
li $v0, 1
add $a0, $zero, %x
syscall
.end_macro

print_int ($s0)
print_int (10)