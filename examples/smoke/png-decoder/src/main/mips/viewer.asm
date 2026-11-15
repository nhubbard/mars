# Open this file with MARS Run or Debug.
# mars:tool bitmap width=1024 height=1024 unitWidth=2 unitHeight=2 base=heap
# Arguments: [PNG path [--scalar]]. The default is torture.png with MSA enabled.
.eqv FRAME_BYTES 1048576
.eqv FILE_BYTES 1048576
.eqv WORK_BYTES 2884096         # framebuffer + file + 512 scanlines of 1537 bytes

.data
default_file: .asciiz "torture.png"
scalar_option: .asciiz "--scalar"
bad_arguments: .asciiz "Usage: viewer.asm [PNG path [--scalar]].\n"
reading: .asciiz "Reading PNG...\n"
checking: .asciiz "Checking PNG chunks...\n"
inflating: .asciiz "Decompressing image...\n"
drawing: .asciiz "PNG verified. Drawing pixels...\n"
done: .asciiz "PNG displayed.\n"
bad_file: .asciiz "Cannot read PNG (missing, empty, or larger than 1 MiB).\n"
bad_png: .asciiz "Invalid PNG or corrupt compressed image.\n"
bad_format: .asciiz "Expected a non-interlaced 8-bit RGB PNG, up to 512 x 512.\n"

.text
.globl main
main:
    li $s6, 1                     # enable MSA unless --scalar was supplied
    la $s3, default_file
    beqz $a0, get_workspace
    nop
    lw $s3, 0($a1)
    li $t0, 1
    beq $a0, $t0, get_workspace
    nop
    li $t0, 2
    bne $a0, $t0, argument_error
    nop
    lw $t0, 4($a1)
    la $t1, scalar_option
check_option:
    lbu $t2, 0($t0)
    lbu $t3, 0($t1)
    bne $t2, $t3, argument_error
    nop
    daddiu $t0, $t0, 1
    daddiu $t1, $t1, 1
    bnez $t3, check_option
    nop
    move $s6, $zero
get_workspace:
    la $a0, reading
    li $v0, 4
    syscall
    li $v0, 9
    li $a0, WORK_BYTES
    syscall
    move $s0, $v0                 # framebuffer starts at the heap base
    li $t0, FRAME_BYTES
    addu $s1, $s0, $t0             # file bytes, then compacted IDAT bytes
    li $t0, FILE_BYTES
    addu $s2, $s1, $t0             # filtered image bytes

    move $a0, $s3
    move $a1, $zero
    move $a2, $zero
    li $v0, 13
    syscall
    bltz $v0, file_error
    nop
    move $s4, $v0
    move $s5, $zero
read_file:
    move $a0, $s4
    addu $a1, $s1, $s5
    li $a2, FILE_BYTES
    subu $a2, $a2, $s5
    li $v0, 14
    syscall
    bltz $v0, file_error
    nop
    beqz $v0, close_file
    nop
    addu $s5, $s5, $v0
    li $t0, FILE_BYTES
    bne $s5, $t0, read_file
    nop
    # Probe one more byte without overwriting the accepted input.
    move $a0, $s4
    move $a1, $s2
    li $a2, 1
    li $v0, 14
    syscall
    bnez $v0, file_error
    nop
close_file:
    move $a0, $s4
    li $v0, 16
    syscall
    beqz $s5, file_error
    nop
    la $a0, checking
    li $v0, 4
    syscall
    move $a0, $s1
    move $a1, $s5
    jal parse_png
    nop
    move $a1, $v0                 # parse_png returns the compressed byte count
    la $a0, inflating
    li $v0, 4
    syscall
    move $a0, $s1
    move $a2, $s2
    lw $t0, png_row_bytes
    addiu $t0, $t0, 1
    lw $t1, png_height
    mul $a3, $t0, $t1
    jal inflate_zlib
    nop
    move $a0, $s2
    move $a1, $s6
    jal unfilter_png
    nop

    la $a0, drawing
    li $v0, 4
    syscall
    move $a0, $s2
    move $a1, $s0
    move $a2, $s6
    jal blit_rgb
    nop
    la $a0, done
    li $v0, 4
    syscall
    li $a0, 0
    b exit_program
    nop

argument_error:
    la $a0, bad_arguments
    b report_error
    nop
file_error:
    la $a0, bad_file
    b report_error
    nop
png_invalid:
    la $a0, bad_png
    b report_error
    nop
png_unsupported:
    la $a0, bad_format
report_error:
    li $v0, 4
    syscall
    li $a0, 1
exit_program:
    li $v0, 17
    syscall

.include "png.s"
.include "deflate.s"
