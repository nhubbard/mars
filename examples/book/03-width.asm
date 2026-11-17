# Book lesson 3: the same starting bits, two different result widths.
.text
.globl main
main:
    li $t0, 0x7fffffff
word_step:
    addiu $t1, $t0, 1
doubleword_step:
    daddiu $t2, $t0, 1
done:
    li $v0, 10
    syscall
