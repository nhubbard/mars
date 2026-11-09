# Enable "Connect Scavenger Hunt from launch" in the MIPS Run configuration.
# Watch the map and scoreboard in View > Tool Windows > MARS Tools.
.eqv HUNT_ADMIN 0xffffe000
.eqv HUNT_PLAYERS 0xffff8000
.eqv HUNT_STRIDE 0x400

.macro select_player (%token, %id)
    li $t0, %token
    sw $t0, 0($s0)
    li $t0, %id
    sw $t0, 4($s0)
.end_macro

.data
success: .asciiz "Round complete. Open Scavenger Hunt to inspect the scoreboard.\n"
failure: .asciiz "Scavenger Hunt did not acknowledge the move or task. Check the run configuration.\n"

.text
.globl main
main:
    li $s0, HUNT_ADMIN
    li $s1, HUNT_PLAYERS
    li $t0, 1
    sw $t0, 8($s0)
    li $t0, 40
    sw $t0, 12($s0)

    select_player (1, 0)
    li $t0, 0x349bdf
    sw $t0, 0x1c($s1)
    li $a0, 2
    jal walk
    nop

    # Sort this player's real task in place, then submit it for more energy.
    lw $t2, 0x20($s1)
    addiu $t2, $t2, -1
sort_pass:
    move $t3, $t2
    addiu $t4, $s1, 0x128
sort_pair:
    lw $t5, 0($t4)
    lw $t6, 4($t4)
    ble $t5, $t6, keep_order
    nop
    sw $t6, 0($t4)
    sw $t5, 4($t4)
keep_order:
    addiu $t4, $t4, 4
    addiu $t3, $t3, -1
    bgtz $t3, sort_pair
    nop
    addiu $t2, $t2, -1
    bgtz $t2, sort_pass
    nop

submit_task:
    li $t0, 1
    sw $t0, 0x124($s1)
    lw $t1, 0x124($s1)
    bnez $t1, fail
    nop

    select_player (2, 1)
    addiu $s1, $s1, HUNT_STRIDE
    li $t0, 0xe57a43
    sw $t0, 0x1c($s1)
    li $a0, -2
    jal walk
    nop

    li $v0, 4
    la $a0, success
    syscall
    li $v0, 10
    syscall

# Spend one move marking the start, then walk east or west for 19 more moves.
walk:
    li $t2, 20
    li $t0, 1
    lw $t1, 0($s1)
    sw $t1, 8($s1)
    lw $t1, 4($s1)
    sw $t1, 12($s1)
walk_step:
    sw $t0, 0x10($s1)
    lw $t1, 0x10($s1)
    bnez $t1, fail
    nop
    addiu $t2, $t2, -1
    beqz $t2, return
    nop
    lw $t1, 0($s1)
    addu $t1, $t1, $a0
    sw $t1, 8($s1)
    b walk_step
    nop
return:
    jr $ra
    nop

fail:
    li $v0, 4
    la $a0, failure
    syscall
    li $a0, 1
    li $v0, 17
    syscall
