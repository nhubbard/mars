main:
    j print_one

print_one:
    li $v0, 1
    li $a0, 1
    syscall
    j print_two

print_two:
    li $v0, 1
    li $a0, 2
    syscall
    li $v0, 10
    syscall