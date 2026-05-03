# Purpose: To illustrate implementing and calling a
# subprogram named PrintNewLine.
.text
main:
 # read an input value from the user
 la $a0, prompt
 jal PrintString
 li $v0, 5
 syscall
 move $s0, $v0

 # print the value back to the user
 jal PrintNewLine
 la $a0, result
 jal PrintString
 li $v0, 1
 move $a0, $s0
 syscall

 # call the Exit subprogram to exit
 jal Exit

.data
 prompt: .asciiz "Please enter an integer: "
 result: .asciiz "You entered: "

# subprogram: PrintNewLine
# purpose: to output a new line to the user console
# input: None
# output: None
# side effects: A new line character is printed to the
# user's console
.text
PrintNewLine:
 li $v0, 4
 la $a0, __PNL_newline
 syscall
 jr $ra
.data
 __PNL_newline: .asciiz "\n"

# subprogram: PrintString
# purpose: To print a string to the console
# input: $a0 - The address of the string to print.
# returns: None
# side effects: The String is printed to the console.
.text
PrintString:
 addi $v0, $zero, 4

# subprogram: Exit
# purpose: to use syscall service 10 to exit a program
# input: None
# output: None
# side effects: The program is exited
.text
Exit:
li $v0, 10
syscall