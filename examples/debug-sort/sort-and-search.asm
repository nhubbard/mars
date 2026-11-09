# Run this file and enter a signed integer at the prompt.
# Try 7, 5, or -8. Search returns the first matching index, or -1.
# For debugging, set a breakpoint on shift_word, search_probe, or report_ready.
# In Debug Memory, Go to Region > Data starts at values in the default layout.
# The search index, sum, and average follow the ten array words.
.include "console.s"
.eqv COUNT 10

.data
.align 2
values:         .word 19, -4, 7, 0, 23, 7, -8, 12, 42, 3
result_index:   .word -1
result_sum:     .word 0
result_average: .float 0.0
before_msg:     .asciiz "Before: "
after_msg:      .asciiz "After:  "
prompt:         .asciiz "Search for an integer: "
index_msg:      .asciiz "First index: "
sum_msg:        .asciiz "Sum: "
average_msg:    .asciiz "Average: "
space:          .asciiz " "
newline:        .asciiz "\n"

.text
.globl main
main:
    print_text (before_msg)
    la $a0, values
    li $a1, COUNT
    jal print_array
    nop

    la $a0, values
    li $a1, COUNT
    jal insertion_sort
    nop

    print_text (after_msg)
    la $a0, values
    li $a1, COUNT
    jal print_array
    nop

    print_text (prompt)
    li $v0, 5
    syscall
    move $a2, $v0
    la $a0, values
    li $a1, COUNT
    jal lower_bound_search
    nop
    sw $v0, result_index

    la $a0, values
    li $a1, COUNT
    jal sum_array
    nop
    sw $v0, result_sum
    mtc1 $v0, $f0
    cvt.s.w $f0, $f0
    li $t0, COUNT
    mtc1 $t0, $f2
    cvt.s.w $f2, $f2
    div.s $f12, $f0, $f2
    s.s $f12, result_average

report_ready: print_text (index_msg)
    lw $t0, result_index
    print_int ($t0)
    print_text (newline)
    print_text (sum_msg)
    lw $t0, result_sum
    print_int ($t0)
    print_text (newline)
    print_text (average_msg)
    li $v0, 2
    syscall
    print_text (newline)
    li $v0, 10
    syscall

# a0 = base address, a1 = length. Uses signed comparisons and sorts in place.
insertion_sort:
    li $t0, 1
sort_next:
    bge $t0, $a1, sort_done
    nop
    sll $t1, $t0, 2
    addu $t1, $a0, $t1
    lw $t2, 0($t1)
    move $t3, $t0
sort_shift:
    beqz $t3, insert_key
    nop
    lw $t4, -4($t1)
    ble $t4, $t2, insert_key
    nop
shift_word: sw $t4, 0($t1)
    addiu $t1, $t1, -4
    addiu $t3, $t3, -1
    b sort_shift
    nop
insert_key:
    sw $t2, 0($t1)
    addiu $t0, $t0, 1
    b sort_next
    nop
sort_done:
    jr $ra
    nop

# a0 = sorted array, a1 = length, a2 = key. The search interval is [lo, hi).
lower_bound_search:
    move $t0, $zero
    move $t1, $a1
search_loop:
    bge $t0, $t1, search_check
    nop
    subu $t2, $t1, $t0
    srl $t2, $t2, 1
    addu $t2, $t2, $t0
    sll $t3, $t2, 2
    addu $t3, $a0, $t3
search_probe: lw $t4, 0($t3)
    blt $t4, $a2, search_right
    nop
    move $t1, $t2
    b search_loop
    nop
search_right:
    addiu $t0, $t2, 1
    b search_loop
    nop
search_check:
    li $v0, -1
    beq $t0, $a1, search_return
    nop
    sll $t3, $t0, 2
    addu $t3, $a0, $t3
    lw $t4, 0($t3)
    bne $t4, $a2, search_return
    nop
    move $v0, $t0
search_return:
    jr $ra
    nop

sum_array:
    move $v0, $zero
sum_next:
    beqz $a1, sum_done
    nop
    lw $t0, 0($a0)
    addu $v0, $v0, $t0
    addiu $a0, $a0, 4
    addiu $a1, $a1, -1
    b sum_next
    nop
sum_done:
    jr $ra
    nop

print_array:
    move $t0, $a0
    move $t1, $a1
print_next:
    beqz $t1, print_done
    nop
    lw $t2, 0($t0)
    print_int ($t2)
    addiu $t0, $t0, 4
    addiu $t1, $t1, -1
    beqz $t1, print_done
    nop
    print_text (space)
    b print_next
    nop
print_done:
    print_text (newline)
    jr $ra
    nop
