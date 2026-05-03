# Purpose: To show the difference in result if
# ordering of multiplication and division
# are reversed.
.text
.globl main
main:
  addi $s0, $zero, 10 # Store 10 and 3 in registers $s0 and $s1
  addi $s1, $zero, 3

  div $s2, $s0, $s1 # Write out (10/3) * 3
  mul $s2, $s2, $s1
  addi $v0, $zero, 4
  la $a0, result1
  syscall
  addi $v0, $zero, 1
  move $a0, $s2
  syscall

  mul $s2, $s0, $s1 # Write out (10*3)/3
  div $s2, $s2, $s1
  addi $v0, $zero, 4
  la $a0, result2
  syscall
  addi $v0, $zero, 1
  move $a0, $s2
  syscall

  addi $v0, $zero, 10 # Exit program
  syscall

.data
result1: .asciiz "\n(10/3)*3 = "
result2: .asciiz "\n(10*3)/3 = "