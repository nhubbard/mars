.macro print_text (%label)
    la $a0, %label
    li $v0, 4
    syscall
.end_macro

.macro print_int (%register)
    move $a0, %register
    li $v0, 1
    syscall
.end_macro
