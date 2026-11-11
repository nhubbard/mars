.data
.align 2
literal_tree: .space 640          # 16 word counts, then up to 288 halfword symbols
distance_tree: .space 128
alphabet_tree: .space 104
tree_offsets: .space 64
code_lengths: .space 320
alphabet_lengths: .space 19
alphabet_order: .byte 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
length_extra: .byte 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2
              .byte 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
distance_extra: .byte 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6
                .byte 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
.align 1
length_base: .half 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31
             .half 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
distance_base: .half 1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129
               .half 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097
               .half 6145, 8193, 12289, 16385, 24577

.text
# a0 = zlib data, a1 = its length, a2 = output, a3 = exact expected output length.
# Reader state: s0 input cursor, s1 input end, s2 bits, s3 bit count.
# Writer state: s4 output cursor, s5 output end, s7 output start. s6 is BFINAL.
inflate_zlib:
    addiu $sp, $sp, -64
    sw $ra, 60($sp)
    sw $s0, 56($sp)
    sw $s1, 52($sp)
    sw $s2, 48($sp)
    sw $s3, 44($sp)
    sw $s4, 40($sp)
    sw $s5, 36($sp)
    sw $s6, 32($sp)
    sw $s7, 28($sp)
    lbu $t0, 0($a0)
    lbu $t1, 1($a0)
    andi $t2, $t0, 15
    li $t3, 8
    bne $t2, $t3, png_invalid
    nop
    sltiu $t2, $t0, 128
    beqz $t2, png_invalid
    nop
    andi $t2, $t1, 32
    bnez $t2, png_invalid         # PNG zlib streams do not use preset dictionaries
    nop
    sll $t0, $t0, 8
    or $t0, $t0, $t1
    li $t1, 31
    divu $t0, $t1
    mfhi $t0
    bnez $t0, png_invalid
    nop
    addiu $s0, $a0, 2
    addu $s1, $a0, $a1
    addiu $s1, $s1, -4
    move $s2, $zero
    move $s3, $zero
    move $s4, $a2
    addu $s5, $a2, $a3
    move $s7, $a2
deflate_block:
    li $a0, 3
    jal read_bits
    nop
    andi $s6, $v0, 1
    srl $t0, $v0, 1
    beqz $t0, stored_block
    nop
    li $t1, 1
    beq $t0, $t1, fixed_block
    nop
    li $t1, 2
    beq $t0, $t1, dynamic_block
    nop
    b png_invalid
    nop

stored_block:
    move $s2, $zero
    move $s3, $zero
    li $a0, 16
    jal read_bits
    nop
    sw $v0, 0($sp)
    li $a0, 16
    jal read_bits
    nop
    lw $t0, 0($sp)
    xor $t1, $t0, $v0
    li $t2, 65535
    bne $t1, $t2, png_invalid
    nop
    subu $t1, $s1, $s0
    bgtu $t0, $t1, png_invalid
    nop
    subu $t1, $s5, $s4
    bgtu $t0, $t1, png_invalid
    nop
    addu $t1, $s0, $t0
stored_copy:
    beq $s0, $t1, block_done
    nop
    lbu $t2, 0($s0)
    sb $t2, 0($s4)
    addiu $s0, $s0, 1
    addiu $s4, $s4, 1
    b stored_copy
    nop

fixed_block:
    la $t0, code_lengths
    move $t1, $zero
    li $t2, 288
fixed_literal_length:
    li $t3, 8
    sltiu $t4, $t1, 144
    bnez $t4, fixed_store_length
    nop
    li $t3, 9
    sltiu $t4, $t1, 256
    bnez $t4, fixed_store_length
    nop
    li $t3, 7
    sltiu $t4, $t1, 280
    bnez $t4, fixed_store_length
    nop
    li $t3, 8
fixed_store_length:
    sb $t3, 0($t0)
    addiu $t0, $t0, 1
    addiu $t1, $t1, 1
    bne $t1, $t2, fixed_literal_length
    nop
    li $t1, 32
    li $t3, 5
fixed_distance_length:
    sb $t3, 0($t0)
    addiu $t0, $t0, 1
    addiu $t1, $t1, -1
    bnez $t1, fixed_distance_length
    nop
    li $t0, 288
    sw $t0, 0($sp)
    li $t0, 32
    sw $t0, 4($sp)
    b build_data_trees
    nop

dynamic_block:
    li $a0, 5
    jal read_bits
    nop
    addiu $v0, $v0, 257
    sltiu $t0, $v0, 287
    beqz $t0, png_invalid
    nop
    sw $v0, 0($sp)               # literal code count
    li $a0, 5
    jal read_bits
    nop
    addiu $v0, $v0, 1
    sw $v0, 4($sp)               # distance code count
    li $a0, 4
    jal read_bits
    nop
    addiu $v0, $v0, 4
    sw $v0, 8($sp)               # code length alphabet count
    la $t0, alphabet_lengths
    li $t1, 19
clear_alphabet:
    sb $zero, 0($t0)
    addiu $t0, $t0, 1
    addiu $t1, $t1, -1
    bnez $t1, clear_alphabet
    nop
    sw $zero, 12($sp)
read_alphabet:
    li $a0, 3
    jal read_bits
    nop
    lw $t0, 12($sp)
    la $t1, alphabet_order
    addu $t1, $t1, $t0
    lbu $t1, 0($t1)
    la $t2, alphabet_lengths
    addu $t2, $t2, $t1
    sb $v0, 0($t2)
    addiu $t0, $t0, 1
    sw $t0, 12($sp)
    lw $t1, 8($sp)
    bne $t0, $t1, read_alphabet
    nop
    la $a0, alphabet_lengths
    li $a1, 19
    la $a2, alphabet_tree
    jal build_tree
    nop
    lw $t0, 0($sp)
    lw $t1, 4($sp)
    addu $t0, $t0, $t1
    la $t1, code_lengths
    addu $t0, $t0, $t1
    sw $t0, 8($sp)               # end of combined code lengths
    sw $t1, 12($sp)              # code length cursor
read_length:
    la $a0, alphabet_tree
    jal decode_symbol
    nop
    sltiu $t0, $v0, 16
    beqz $t0, length_repeat
    nop
    move $t1, $v0
    li $t2, 1
    b store_lengths
    nop
length_repeat:
    li $t0, 16
    bne $v0, $t0, length_repeat_zero
    nop
    lw $t0, 12($sp)
    la $t1, code_lengths
    beq $t0, $t1, png_invalid
    nop
    lbu $t1, -1($t0)
    sw $t1, 16($sp)
    li $a0, 2
    jal read_bits
    nop
    addiu $t2, $v0, 3
    lw $t1, 16($sp)
    b store_lengths
    nop
length_repeat_zero:
    li $t0, 17
    bne $v0, $t0, length_repeat_long
    nop
    li $a0, 3
    jal read_bits
    nop
    addiu $t2, $v0, 3
    move $t1, $zero
    b store_lengths
    nop
length_repeat_long:
    li $a0, 7
    jal read_bits
    nop
    addiu $t2, $v0, 11
    move $t1, $zero
store_lengths:
    lw $t0, 12($sp)
    lw $t3, 8($sp)
    subu $t4, $t3, $t0
    bgtu $t2, $t4, png_invalid
    nop
length_store_byte:
    sb $t1, 0($t0)
    addiu $t0, $t0, 1
    addiu $t2, $t2, -1
    bnez $t2, length_store_byte
    nop
    sw $t0, 12($sp)
    bne $t0, $t3, read_length
    nop
    la $t0, code_lengths
    lbu $t1, 256($t0)
    beqz $t1, png_invalid         # each compressed block needs an end-of-block code
    nop
build_data_trees:
    la $a0, code_lengths
    lw $a1, 0($sp)
    la $a2, literal_tree
    jal build_tree
    nop
    la $a0, code_lengths
    lw $t0, 0($sp)
    addu $a0, $a0, $t0
    lw $a1, 4($sp)
    la $a2, distance_tree
    jal build_tree
    nop

inflate_symbol:
    la $a0, literal_tree
    jal decode_symbol
    nop
    sltiu $t0, $v0, 256
    bnez $t0, emit_literal
    nop
    li $t0, 256
    beq $v0, $t0, block_done
    nop
    addiu $t0, $v0, -257
    sltiu $t1, $t0, 29
    beqz $t1, png_invalid
    nop
    la $t1, length_extra
    addu $t1, $t1, $t0
    lbu $a0, 0($t1)
    la $t1, length_base
    sll $t0, $t0, 1
    addu $t1, $t1, $t0
    lhu $t1, 0($t1)
    sw $t1, 16($sp)
    jal read_bits
    nop
    lw $t1, 16($sp)
    addu $t1, $t1, $v0
    sw $t1, 16($sp)
    la $a0, distance_tree
    jal decode_symbol
    nop
    sltiu $t0, $v0, 30
    beqz $t0, png_invalid
    nop
    la $t0, distance_extra
    addu $t0, $t0, $v0
    lbu $a0, 0($t0)
    la $t0, distance_base
    sll $t1, $v0, 1
    addu $t0, $t0, $t1
    lhu $t0, 0($t0)
    sw $t0, 20($sp)
    jal read_bits
    nop
    lw $t0, 20($sp)
    addu $t0, $t0, $v0
    subu $t1, $s4, $s7
    bgtu $t0, $t1, png_invalid
    nop
    lw $t1, 16($sp)
    subu $t2, $s5, $s4
    bgtu $t1, $t2, png_invalid
    nop
    subu $t0, $s4, $t0
    addu $t1, $s4, $t1
copy_match:
    lbu $t2, 0($t0)              # forward copy also handles overlapping matches
    sb $t2, 0($s4)
    addiu $t0, $t0, 1
    addiu $s4, $s4, 1
    bne $s4, $t1, copy_match
    nop
    b inflate_symbol
    nop
emit_literal:
    beq $s4, $s5, png_invalid
    nop
    sb $v0, 0($s4)
    addiu $s4, $s4, 1
    b inflate_symbol
    nop
block_done:
    beqz $s6, deflate_block
    nop
    bne $s4, $s5, png_invalid
    nop
    bne $s0, $s1, png_invalid
    nop
    move $a0, $s7
    move $a1, $s5
    jal adler32
    nop
    sw $v0, 0($sp)
    move $a0, $s1
    jal be32
    nop
    lw $t0, 0($sp)
    bne $v0, $t0, png_invalid
    nop
    lw $ra, 60($sp)
    lw $s0, 56($sp)
    lw $s1, 52($sp)
    lw $s2, 48($sp)
    lw $s3, 44($sp)
    lw $s4, 40($sp)
    lw $s5, 36($sp)
    lw $s6, 32($sp)
    lw $s7, 28($sp)
    addiu $sp, $sp, 64
    jr $ra
    nop

# Read up to 16 little-endian packed bits, without reading the zlib checksum.
read_bits:
    bgeu $s3, $a0, bits_ready
    nop
    beq $s0, $s1, png_invalid
    nop
    lbu $t0, 0($s0)
    addiu $s0, $s0, 1
    sllv $t0, $t0, $s3
    or $s2, $s2, $t0
    addiu $s3, $s3, 8
    b read_bits
    nop
bits_ready:
    li $t0, 1
    sllv $t0, $t0, $a0
    addiu $t0, $t0, -1
    and $v0, $s2, $t0
    srlv $s2, $s2, $a0
    subu $s3, $s3, $a0
    jr $ra
    nop

# a0 = length bytes, a1 = number of symbols, a2 = output tree.
# Count codes by length and place symbols in canonical order (length, then symbol).
build_tree:
    move $t0, $a2
    addiu $t1, $a2, 64
tree_clear:
    sw $zero, 0($t0)
    addiu $t0, $t0, 4
    bne $t0, $t1, tree_clear
    nop
    move $t0, $a0
    addu $t1, $a0, $a1
tree_count:
    lbu $t2, 0($t0)
    sll $t2, $t2, 2
    addu $t2, $t2, $a2
    lw $t3, 0($t2)
    addiu $t3, $t3, 1
    sw $t3, 0($t2)
    addiu $t0, $t0, 1
    bne $t0, $t1, tree_count
    nop
    addiu $t0, $a2, 4
    addiu $t1, $a2, 64
    la $t2, tree_offsets
    addiu $t2, $t2, 4
    move $t3, $zero
    li $t4, 1
tree_prefix:
    sw $t3, 0($t2)
    lw $t5, 0($t0)
    addu $t3, $t3, $t5
    sll $t4, $t4, 1
    subu $t4, $t4, $t5
    bltz $t4, png_invalid         # too many codes for this bit length
    nop
    addiu $t0, $t0, 4
    addiu $t2, $t2, 4
    bne $t0, $t1, tree_prefix
    nop
    la $t0, tree_offsets
    addiu $t1, $a2, 64
    move $t2, $zero
tree_symbol:
    lbu $t3, 0($a0)
    beqz $t3, tree_next
    nop
    sll $t3, $t3, 2
    addu $t3, $t3, $t0
    lw $t4, 0($t3)
    addiu $t5, $t4, 1
    sw $t5, 0($t3)
    sll $t4, $t4, 1
    addu $t4, $t4, $t1
    sh $t2, 0($t4)
tree_next:
    addiu $a0, $a0, 1
    addiu $t2, $t2, 1
    bne $t2, $a1, tree_symbol
    nop
    jr $ra
    nop

# a0 = tree. Huffman code bits arrive most significant bit first.
decode_symbol:
    move $t0, $zero               # current code
    move $t1, $zero               # first code of current length
    move $t2, $zero               # index of that code in the symbol table
    addiu $t3, $a0, 4
    addiu $t4, $a0, 64
decode_bit:
    bnez $s3, decode_has_byte
    nop
    beq $s0, $s1, png_invalid
    nop
    lbu $s2, 0($s0)
    addiu $s0, $s0, 1
    li $s3, 8
decode_has_byte:
    andi $t5, $s2, 1
    srl $s2, $s2, 1
    addiu $s3, $s3, -1
    or $t0, $t0, $t5
    lw $t5, 0($t3)
    subu $t6, $t0, $t1
    bltu $t6, $t5, decode_found
    nop
    addu $t1, $t1, $t5
    sll $t1, $t1, 1
    sll $t0, $t0, 1
    addu $t2, $t2, $t5
    addiu $t3, $t3, 4
    bne $t3, $t4, decode_bit
    nop
    b png_invalid
    nop
decode_found:
    addu $t6, $t6, $t2
    sll $t6, $t6, 1
    addu $t6, $t6, $t4
    lhu $v0, 0($t6)
    jr $ra
    nop

# Both sums stay below twice 65521, so each reduction needs at most one subtraction.
adler32:
    li $t0, 1
    move $t1, $zero
    li $t2, 65521
adler_byte:
    beq $a0, $a1, adler_done
    nop
    lbu $t3, 0($a0)
    addu $t0, $t0, $t3
    sltu $t3, $t0, $t2
    bnez $t3, adler_second
    nop
    subu $t0, $t0, $t2
adler_second:
    addu $t1, $t1, $t0
    sltu $t3, $t1, $t2
    bnez $t3, adler_next
    nop
    subu $t1, $t1, $t2
adler_next:
    addiu $a0, $a0, 1
    b adler_byte
    nop
adler_done:
    sll $v0, $t1, 16
    or $v0, $v0, $t0
    jr $ra
    nop
