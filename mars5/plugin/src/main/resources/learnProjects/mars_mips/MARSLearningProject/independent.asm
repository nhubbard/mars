.data
prompt:  .asciiz "Enter a small integer: "
answer:  .asciiz "Three times your number plus one is: "
newline: .asciiz "\n"

.text
.globl main
main:
    # Write the input, 3x+1 calculation and output here.
    # Accept an integer from -1000 through 1000 and exit normally.
    li $v0, 10
    syscall
