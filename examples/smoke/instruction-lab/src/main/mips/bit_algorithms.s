.text

# $a0: word. $v0: number of one bits.
population_count:
    move $v0, $zero
count_bit:
    beqz $a0, count_done
    nop
    addiu $t1, $a0, -1
    and $a0, $a0, $t1
    addiu $v0, $v0, 1
    b count_bit
    nop
count_done:
    jr $ra
    nop

# Reflected CRC-32/ISO-HDLC (polynomial 0xedb88320).
# $a0: UTF-8 buffer ending with NUL or newline. $v0: CRC, $v1: byte count.
crc32:
    li $v0, -1
    move $v1, $zero
    li $t4, 0xedb88320
crc_next:
    lbu $t0, 0($a0)
    beqz $t0, crc_done
    nop
    beq $t0, '\n', crc_done
    nop
    xor $v0, $v0, $t0
    li $t1, 8
crc_bit:
    andi $t2, $v0, 1
    srl $v0, $v0, 1
    beqz $t2, crc_skip_polynomial
    nop
    xor $v0, $v0, $t4
crc_skip_polynomial:
    addiu $t1, $t1, -1
    bnez $t1, crc_bit
    nop
    addiu $a0, $a0, 1
    addiu $v1, $v1, 1
    b crc_next
    nop
crc_done:
    not $v0, $v0
    jr $ra
    nop
