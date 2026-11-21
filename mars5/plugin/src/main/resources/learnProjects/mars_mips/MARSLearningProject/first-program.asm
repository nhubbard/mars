.data
prompt:  .asciiz "Enter a small integer: "
answer:  .asciiz "Twice your number plus one is: "
newline: .asciiz "\n"

.text
.globl main
main:
    la $a0, prompt
    li $v0, 4
    syscall
    li $v0, 5
    syscall
calculate:
    addu $t0, $v0, $v0
    addiu $t0, $t0, 1
report:
    la $a0, answer
    li $v0, 4
    syscall
    move $a0, $t0
    li $v0, 1
    syscall
    la $a0, newline
    li $v0, 4
    syscall
done:
    li $v0, 10
    syscall
