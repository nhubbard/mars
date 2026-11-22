.data
prompt:   .asciiz "Enter an integer from 0 to 255: "
unsigned: .asciiz "Unsigned byte: "
signed:   .asciiz "Signed byte: "
newline:  .asciiz "\n"

.text
.globl main
main:
    la $a0, prompt
    li $v0, 4
    syscall
    li $v0, 5
    syscall
interpret:
    andi $t0, $v0, 0xff
    seb $t1, $t0
report:
    la $a0, unsigned
    li $v0, 4
    syscall
    move $a0, $t0
    li $v0, 1
    syscall
    la $a0, newline
    li $v0, 4
    syscall
    la $a0, signed
    li $v0, 4
    syscall
    move $a0, $t1
    li $v0, 1
    syscall
    la $a0, newline
    li $v0, 4
    syscall
    li $v0, 10
    syscall
