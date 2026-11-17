# Book lesson 2: load a word, double it, store it, and load it again.
.data
.align 2
input:  .word 21
result: .word 0

.text
.globl main
main:
    la $t0, input
load_value:
    lw $t1, 0($t0)
    addu $t2, $t1, $t1
store_result:
    sw $t2, 4($t0)
reload_result:
    lw $t3, 4($t0)
done:
    li $v0, 10
    syscall
