.data
.align 2
png_width: .word 0
png_height: .word 0
png_row_bytes: .word 0
crc_table: .space 1024
zero_row: .space 1536

# Each mask selects B, G, R, 0 for four consecutive pixels.
# VSHF.B indices 0..15 select the right input and 16..31 select the left.
# 0x80 selects zero. The middle masks cross input-vector boundaries.
rgb_shuffle:
    .byte 2, 1, 0, 0x80, 5, 4, 3, 0x80, 8, 7, 6, 0x80, 11, 10, 9, 0x80
    .byte 14, 13, 12, 0x80, 17, 16, 15, 0x80, 20, 19, 18, 0x80, 23, 22, 21, 0x80
    .byte 10, 9, 8, 0x80, 13, 12, 11, 0x80, 16, 15, 14, 0x80, 19, 18, 17, 0x80
    .byte 6, 5, 4, 0x80, 9, 8, 7, 0x80, 12, 11, 10, 0x80, 15, 14, 13, 0x80

.text
# Read a network-order word, regardless of the simulator's byte order.
be32:
    lbu $v0, 0($a0)
    lbu $t0, 1($a0)
    lbu $t1, 2($a0)
    lbu $t2, 3($a0)
    sll $v0, $v0, 24
    sll $t0, $t0, 16
    sll $t1, $t1, 8
    or $v0, $v0, $t0
    or $v0, $v0, $t1
    or $v0, $v0, $t2
    jr $ra
    nop

# Compact consecutive IDAT payloads toward the start of the file after checking their CRCs.
# a0 = file, a1 = file size; v0 = compacted zlib stream length.
parse_png:
    addiu $sp, $sp, -40
    sw $ra, 36($sp)
    sw $s0, 32($sp)
    sw $s1, 28($sp)
    sw $s2, 24($sp)
    sw $s3, 20($sp)
    sw $s4, 16($sp)
    sw $s5, 12($sp)
    sw $s6, 8($sp)
    sw $s7, 4($sp)
    move $s0, $a0
    addu $s1, $a0, $a1
    sltiu $t0, $a1, 33
    bnez $t0, png_invalid
    nop
    jal be32
    nop
    li $t0, 0x89504e47
    bne $v0, $t0, png_invalid
    nop
    addiu $a0, $s0, 4
    jal be32
    nop
    li $t0, 0x0d0a1a0a
    bne $v0, $t0, png_invalid
    nop
    jal make_crc_table
    nop
    addiu $s2, $s0, 8
    move $s3, $s0
    move $s7, $zero
chunk_next:
    subu $t0, $s1, $s2
    sltiu $t0, $t0, 12
    bnez $t0, png_invalid
    nop
    move $a0, $s2
    jal be32
    nop
    move $s4, $v0
    subu $t0, $s1, $s2
    addiu $t0, $t0, -12
    bgtu $s4, $t0, png_invalid
    nop
    addiu $a0, $s2, 4
    jal be32
    nop
    move $s5, $v0
    addiu $s6, $s2, 8
    addiu $a0, $s2, 4
    addu $a1, $s6, $s4
    jal crc32
    nop
    sw $v0, 0($sp)
    addu $a0, $s6, $s4
    jal be32
    nop
    lw $t0, 0($sp)
    bne $v0, $t0, png_invalid
    nop

    addiu $t0, $s0, 8
    bne $s2, $t0, chunk_after_header
    nop
    li $t0, 0x49484452             # IHDR
    bne $s5, $t0, png_invalid
    nop
    li $t0, 13
    bne $s4, $t0, png_invalid
    nop
    move $a0, $s6
    jal be32
    nop
    addiu $t0, $v0, -1
    sltiu $t0, $t0, 512
    beqz $t0, png_unsupported
    nop
    sw $v0, png_width
    sll $t0, $v0, 1
    addu $t0, $t0, $v0
    sw $t0, png_row_bytes
    addiu $a0, $s6, 4
    jal be32
    nop
    addiu $t0, $v0, -1
    sltiu $t0, $t0, 512
    beqz $t0, png_unsupported
    nop
    sw $v0, png_height
    addiu $a0, $s6, 8
    jal be32
    nop
    li $t0, 0x08020000             # 8-bit, RGB, standard compression and filter methods
    bne $v0, $t0, png_unsupported
    nop
    lbu $t0, 12($s6)
    bnez $t0, png_unsupported     # Adam7 is outside this viewer's profile
    nop
    b chunk_advance
    nop
chunk_after_header:
    li $t0, 0x49444154             # IDAT
    beq $s5, $t0, chunk_data
    nop
    li $t0, 0x49454e44             # IEND
    beq $s5, $t0, chunk_end
    nop
    li $t0, 0x504c5445             # optional truecolor palette
    beq $s5, $t0, chunk_palette
    nop
    srl $t0, $s5, 24
    andi $t0, $t0, 32
    beqz $t0, png_invalid         # an unknown critical chunk, including a repeated IHDR
    nop
    beqz $s7, chunk_advance
    nop
    li $s7, 2                     # IDAT chunks must be consecutive
    b chunk_advance
    nop
chunk_palette:
    bnez $s7, png_invalid
    nop
    beqz $s4, png_invalid
    nop
    li $t0, 768
    bgtu $s4, $t0, png_invalid
    nop
    li $t0, 3
    modu $t0, $s4, $t0
    bnez $t0, png_invalid
    nop
    b chunk_advance
    nop
chunk_data:
    li $t0, 2
    beq $s7, $t0, png_invalid
    nop
    li $s7, 1
    move $t0, $s6
    addu $t1, $s6, $s4
chunk_copy:
    beq $t0, $t1, chunk_advance
    nop
    lbu $t2, 0($t0)
    sb $t2, 0($s3)
    addiu $t0, $t0, 1
    addiu $s3, $s3, 1
    b chunk_copy
    nop
chunk_advance:
    addu $s2, $s6, $s4
    addiu $s2, $s2, 4
    b chunk_next
    nop
chunk_end:
    bnez $s4, png_invalid
    nop
    addiu $t0, $s6, 4
    bne $s1, $t0, png_invalid
    nop
    subu $v0, $s3, $s0
    sltiu $t0, $v0, 6
    bnez $t0, png_invalid
    nop
    lw $ra, 36($sp)
    lw $s0, 32($sp)
    lw $s1, 28($sp)
    lw $s2, 24($sp)
    lw $s3, 20($sp)
    lw $s4, 16($sp)
    lw $s5, 12($sp)
    lw $s6, 8($sp)
    lw $s7, 4($sp)
    addiu $sp, $sp, 40
    jr $ra
    nop

make_crc_table:
    la $t0, crc_table
    li $t1, 0
    li $t2, 256
    li $t3, 0xedb88320
crc_table_entry:
    move $t4, $t1
    li $t5, 8
crc_table_bit:
    andi $t6, $t4, 1
    subu $t6, $zero, $t6
    and $t6, $t6, $t3
    srl $t4, $t4, 1
    xor $t4, $t4, $t6
    addiu $t5, $t5, -1
    bnez $t5, crc_table_bit
    nop
    sw $t4, 0($t0)
    addiu $t0, $t0, 4
    addiu $t1, $t1, 1
    bne $t1, $t2, crc_table_entry
    nop
    jr $ra
    nop

# a0..a1 (exclusive), v0 = reflected CRC-32.
crc32:
    la $t0, crc_table
    li $v0, -1
crc_byte:
    beq $a0, $a1, crc_done
    nop
    lbu $t1, 0($a0)
    xor $t1, $t1, $v0
    andi $t1, $t1, 255
    sll $t1, $t1, 2
    addu $t1, $t1, $t0
    lw $t1, 0($t1)
    srl $v0, $v0, 8
    xor $v0, $v0, $t1
    addiu $a0, $a0, 1
    b crc_byte
    nop
crc_done:
    nor $v0, $v0, $zero
    jr $ra
    nop

# Inverse PNG filters in place. The byte before each row holds the filter number.
# a0 = filtered rows; a1 = enable MSA.
unfilter_png:
    move $v1, $a1
    lw $a1, png_height
    lw $a2, png_row_bytes
    la $a3, zero_row
filter_row:
    lbu $t0, 0($a0)
    sltiu $t1, $t0, 5
    beqz $t1, png_invalid
    nop
    addiu $a0, $a0, 1
    move $t1, $a0                 # destination byte
    move $t2, $a3                 # corresponding byte in the previous row
    addu $t3, $a0, $a2            # end of current row
    beqz $t0, filter_row_done
    nop
    beqz $v1, filter_byte
    nop
    li $t4, 2
    beq $t0, $t4, filter_up_vector
    nop
filter_byte:
    lbu $t4, 0($t2)               # up
    move $t5, $zero               # left
    move $t6, $zero               # upper left
    subu $t7, $t1, $a0
    sltiu $t7, $t7, 3
    bnez $t7, filter_predict
    nop
    lbu $t5, -3($t1)
    lbu $t6, -3($t2)
filter_predict:
    li $t7, 1
    beq $t0, $t7, filter_left
    nop
    li $t7, 2
    beq $t0, $t7, filter_store
    nop
    li $t7, 3
    beq $t0, $t7, filter_average
    nop

    # Paeth compares |up-corner|, |left-corner| and |left+up-2*corner|.
    # Break ties in that order, as required by PNG.
    subu $t7, $t4, $t6
    subu $t8, $t5, $t6
    addu $t9, $t7, $t8
    abs $t7, $t7
    abs $t8, $t8
    abs $t9, $t9
    bgt $t7, $t8, filter_check_up
    nop
    ble $t7, $t9, filter_left
    nop
filter_check_up:
    ble $t8, $t9, filter_store
    nop
    move $t4, $t6
    b filter_store
    nop
filter_left:
    move $t4, $t5
    b filter_store
    nop
filter_average:
    addu $t4, $t4, $t5
    srl $t4, $t4, 1
filter_store:
    lbu $t7, 0($t1)
    addu $t7, $t7, $t4
    sb $t7, 0($t1)
    addiu $t1, $t1, 1
    addiu $t2, $t2, 1
    bne $t1, $t3, filter_byte
    nop
# Up depends only on the previous row. ADDV.B wraps each sum modulo 256.
filter_up_vector:
    dsubu $t4, $t3, $t1
    sltiu $t4, $t4, 16
    bnez $t4, filter_up_tail
    nop
    ld.b $w0, 0($t1)
    ld.b $w1, 0($t2)
    addv.b $w0, $w0, $w1
    st.b $w0, 0($t1)
    daddiu $t1, $t1, 16
    daddiu $t2, $t2, 16
    b filter_up_vector
    nop
filter_up_tail:
    beq $t1, $t3, filter_row_done
    nop
    lbu $t4, 0($t1)
    lbu $t5, 0($t2)
    addu $t4, $t4, $t5
    sb $t4, 0($t1)
    daddiu $t1, $t1, 1
    daddiu $t2, $t2, 1
    b filter_up_tail
    nop
filter_row_done:
    move $a3, $a0
    move $a0, $t3
    addiu $a1, $a1, -1
    bnez $a1, filter_row
    nop
    jr $ra
    nop

# One aligned MARS bitmap word for each RGB pixel. Heap row stride is always 512 words.
# a0 = reconstructed rows; a1 = framebuffer; a2 = enable MSA.
blit_rgb:
    move $v1, $a2
    beqz $v1, blit_setup
    nop
    la $t0, rgb_shuffle
    ld.b $w4, 0($t0)
    ld.b $w5, 16($t0)
    ld.b $w6, 32($t0)
    ld.b $w7, 48($t0)
blit_setup:
    lw $a2, png_width
    lw $a3, png_height
blit_row:
    addiu $a0, $a0, 1
    move $t0, $a1
    move $t1, $a2
    beqz $v1, blit_pixel
    nop
blit_vector:
    sltiu $t2, $t1, 16
    bnez $t2, blit_tail
    nop
    # Read exactly 48 RGB bytes. ST.W writes numeric words in either byte order.
    ld.b $w0, 0($a0)
    ld.b $w1, 16($a0)
    ld.b $w2, 32($a0)
    move.v $w3, $w4
    vshf.b $w3, $w0, $w0
    st.w $w3, 0($t0)
    move.v $w3, $w5
    vshf.b $w3, $w1, $w0
    st.w $w3, 16($t0)
    move.v $w3, $w6
    vshf.b $w3, $w2, $w1
    st.w $w3, 32($t0)
    move.v $w3, $w7
    vshf.b $w3, $w2, $w2
    st.w $w3, 48($t0)
    daddiu $a0, $a0, 48
    daddiu $t0, $t0, 64
    addiu $t1, $t1, -16
    b blit_vector
    nop
blit_tail:
    beqz $t1, blit_row_done
    nop
blit_pixel:
    lbu $t2, 0($a0)
    lbu $t3, 1($a0)
    lbu $t4, 2($a0)
    sll $t2, $t2, 16
    sll $t3, $t3, 8
    or $t2, $t2, $t3
    or $t2, $t2, $t4
    sw $t2, 0($t0)
    addiu $a0, $a0, 3
    addiu $t0, $t0, 4
    addiu $t1, $t1, -1
    bnez $t1, blit_pixel
    nop
blit_row_done:
    addiu $a1, $a1, 2048
    addiu $a3, $a3, -1
    bnez $a3, blit_row
    nop
    jr $ra
    nop
