# MIPS Binary Workbench. Enter signed decimal words, or encode fields directly.
# Open this file in IntelliJ and Run or Debug with the default memory layout.

.eqv PRINT_INT 1
.eqv PRINT_STRING 4
.eqv READ_INT 5
.eqv READ_STRING 8
.eqv EXIT 10
.eqv PRINT_CHAR 11
.eqv PRINT_HEX 34
.eqv PRINT_BINARY 35
.eqv PRINT_UNSIGNED 36
.eqv BUFFER_SIZE 512

.macro text (%label)
    la $a0, %label
    li $v0, PRINT_STRING
    syscall
.end_macro

.macro read_int (%label, %target)
    text (%label)
    li $v0, READ_INT
    syscall
    move %target, $v0
.end_macro

.macro number (%label, %register, %format)
    text (%label)
    move $a0, %register
    li $v0, %format
    syscall
    li $a0, '\n'
    li $v0, PRINT_CHAR
    syscall
.end_macro

.data
banner: .asciiz "\nMIPS Binary Workbench\n"
menu: .asciiz "\n1 Inspect a 32-bit word\n2 CRC-32 of text\n3 Encode an I-format word\n4 Encode an R-format word\n5 Rotate a word\n0 Exit\nChoice: "
word_prompt: .asciiz "Signed decimal word: "
opcode_prompt: .asciiz "Opcode (0..63): "
rs_prompt: .asciiz "rs (0..31): "
rt_prompt: .asciiz "rt (0..31): "
rd_prompt: .asciiz "rd (0..31): "
shift_prompt: .asciiz "Shift amount (0..31): "
function_prompt: .asciiz "Function (0..63): "
immediate_prompt: .asciiz "Immediate (-32768..65535): "
rotate_prompt: .asciiz "Rotate right by (0..31): "
crc_prompt: .asciiz "Text (up to 511 bytes): "
bad_choice: .asciiz "Choose a listed operation.\n"
bad_field: .asciiz "A field is outside its permitted range.\n"
signed_label: .asciiz "Signed decimal: "
unsigned_label: .asciiz "Unsigned decimal: "
hex_label: .asciiz "Hex: "
binary_label: .asciiz "Binary: "
one_bits_label: .asciiz "Set bits: "
opcode_label: .asciiz "Opcode: "
rs_label: .asciiz "rs: "
rt_label: .asciiz "rt: "
rd_label: .asciiz "rd: "
shift_label: .asciiz "Shift amount: "
function_label: .asciiz "Function: "
immediate_label: .asciiz "Signed immediate: "
jump_label: .asciiz "Jump index shifted left by two: "
crc_label: .asciiz "CRC-32: "
byte_count_label: .asciiz "Bytes: "
input: .space BUFFER_SIZE

.text
.globl main
main:
    text (banner)
menu_loop:
    read_int (menu, $t0)
    beqz $t0, quit
    nop
    beq $t0, 1, inspect
    nop
    beq $t0, 2, checksum
    nop
    beq $t0, 3, encode_i
    nop
    beq $t0, 4, encode_r
    nop
    beq $t0, 5, rotate
    nop
    text (bad_choice)
    b menu_loop
    nop

inspect:
    read_int (word_prompt, $a0)
    jal inspect_word
    nop
    b menu_loop
    nop

checksum:
    text (crc_prompt)
    la $a0, input
    li $a1, BUFFER_SIZE
    li $v0, READ_STRING
    syscall
    la $a0, input
    jal crc32
    nop
    move $s0, $v0
    move $s1, $v1
    number (crc_label, $s0, PRINT_HEX)
    number (byte_count_label, $s1, PRINT_UNSIGNED)
    b menu_loop
    nop

encode_i:
    read_int (opcode_prompt, $s0)
    read_int (rs_prompt, $s1)
    read_int (rt_prompt, $s2)
    read_int (immediate_prompt, $s3)
    bgtu $s0, 63, invalid
    nop
    bgtu $s1, 31, invalid
    nop
    bgtu $s2, 31, invalid
    nop
    blt $s3, -32768, invalid
    nop
    bgt $s3, 65535, invalid
    nop
    sll $t0, $s0, 26
    sll $t1, $s1, 21
    sll $t2, $s2, 16
    andi $t3, $s3, 0xffff
    or $t0, $t0, $t1
    or $t0, $t0, $t2
    or $a0, $t0, $t3
    jal inspect_word
    nop
    b menu_loop
    nop

encode_r:
    read_int (rs_prompt, $s1)
    read_int (rt_prompt, $s2)
    read_int (rd_prompt, $s3)
    read_int (shift_prompt, $s4)
    read_int (function_prompt, $s5)
    bgtu $s1, 31, invalid
    nop
    bgtu $s2, 31, invalid
    nop
    bgtu $s3, 31, invalid
    nop
    bgtu $s4, 31, invalid
    nop
    bgtu $s5, 63, invalid
    nop
    sll $t0, $s1, 21
    sll $t1, $s2, 16
    sll $t2, $s3, 11
    sll $t3, $s4, 6
    or $t0, $t0, $t1
    or $t0, $t0, $t2
    or $t0, $t0, $t3
    or $a0, $t0, $s5
    jal inspect_word
    nop
    b menu_loop
    nop

rotate:
    read_int (word_prompt, $s0)
    read_int (rotate_prompt, $s1)
    bgtu $s1, 31, invalid
    nop
    ror $a0, $s0, $s1
    jal inspect_word
    nop
    b menu_loop
    nop

invalid:
    text (bad_field)
    b menu_loop
    nop

quit:
    li $v0, EXIT
    syscall

# Preserve saved registers and return address across the population-count call.
inspect_word:
    addiu $sp, $sp, -8
    sw $s6, 0($sp)
    sw $ra, 4($sp)
    move $s6, $a0
    number (signed_label, $s6, PRINT_INT)
    number (unsigned_label, $s6, PRINT_UNSIGNED)
    number (hex_label, $s6, PRINT_HEX)
    number (binary_label, $s6, PRINT_BINARY)
    move $a0, $s6
    jal population_count
    nop
    move $t0, $v0
    number (one_bits_label, $t0, PRINT_UNSIGNED)
    srl $t0, $s6, 26
    number (opcode_label, $t0, PRINT_UNSIGNED)
    srl $t0, $s6, 21
    andi $t0, $t0, 31
    number (rs_label, $t0, PRINT_UNSIGNED)
    srl $t0, $s6, 16
    andi $t0, $t0, 31
    number (rt_label, $t0, PRINT_UNSIGNED)
    srl $t0, $s6, 11
    andi $t0, $t0, 31
    number (rd_label, $t0, PRINT_UNSIGNED)
    srl $t0, $s6, 6
    andi $t0, $t0, 31
    number (shift_label, $t0, PRINT_UNSIGNED)
    andi $t0, $s6, 63
    number (function_label, $t0, PRINT_UNSIGNED)
    sll $t0, $s6, 16
    sra $t0, $t0, 16
    number (immediate_label, $t0, PRINT_INT)
    sll $t0, $s6, 6
    srl $t0, $t0, 4
    number (jump_label, $t0, PRINT_HEX)
    lw $s6, 0($sp)
    lw $ra, 4($sp)
    addiu $sp, $sp, 8
    jr $ra
    nop

.include "bit_algorithms.s"
