# Enable "Connect Keyboard and Display Simulator from launch" for this program.
# Type in the Keyboard field under MARS Tools. Type ~ to clear or q to exit.

.eqv RECEIVER_CONTROL 0
.eqv RECEIVER_DATA 4
.eqv TRANSMITTER_CONTROL 8
.eqv TRANSMITTER_DATA 12

.data
banner: .asciiz "MARS MMIO terminal\nType here to echo.  ~ clears, q exits.\n> "

.text
.globl main
main:
    lui $s0, 0xffff
print_banner:
    la $a0, banner
    jal print_string
    nop

receive:
    lw $t0, RECEIVER_CONTROL($s0)
    andi $t0, $t0, 1
    beqz $t0, receive
    nop
    lbu $a0, RECEIVER_DATA($s0)
    beq $a0, 'q', done
    nop
    beq $a0, '~', clear
    nop
    jal put_character
    nop
    b receive
    nop

clear:
    li $a0, 12
    jal put_character
    nop
    b print_banner
    nop

done:
    jal wait_for_display
    nop
    li $v0, 10
    syscall

print_string:
    addiu $sp, $sp, -8
    sw $ra, 4($sp)
    sw $s1, 0($sp)
    move $s1, $a0
print_next:
    lbu $a0, 0($s1)
    beqz $a0, printed
    nop
    jal put_character
    nop
    addiu $s1, $s1, 1
    b print_next
    nop
printed:
    lw $s1, 0($sp)
    lw $ra, 4($sp)
    addiu $sp, $sp, 8
    jr $ra
    nop

put_character:
    lw $t0, TRANSMITTER_CONTROL($s0)
    andi $t0, $t0, 1
    beqz $t0, put_character
    nop
    sb $a0, TRANSMITTER_DATA($s0)
    jr $ra
    nop

wait_for_display:
    lw $t0, TRANSMITTER_CONTROL($s0)
    andi $t0, $t0, 1
    beqz $t0, wait_for_display
    nop
    jr $ra
    nop
