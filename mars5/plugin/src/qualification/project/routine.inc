write_result:
    lw $a0, 0($a1)
    li $a1, 1
    move $a2, $zero
    li $v0, 13
    syscall
    bltz $v0, failed
    nop
    move $t0, $v0

    move $a0, $t0
    la $a1, résultat
    li $a2, 20
    li $v0, 15
    syscall
    bne $v0, $a2, failed
    nop

    move $a0, $t0
    li $v0, 16
    syscall
    jr $ra
    nop

failed:
    li $a0, 1
    li $v0, 17
    syscall

.include "legacy.asm"
