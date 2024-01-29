    .data               # Data segment
    .globl globalVar        # Declare globalVar as a global label
globalVar:
    .word 10           # Initialize globalVar with the value 10

    .text               # Code segment
    .globl main         # Declare main as a global label, making it the entry point
main:
    la $a0, globalVar      # Load the address of globalVar into register $a0
    lw $t0, 0($a0)         # Load the word from the address in $a0 into $t0

    addi $t0, $t0, 1       # Increment the value in $t0 by 1
    sw $t0, 0($a0)         # Store the incremented value back into the memory location of globalVar

    move $a0, $t0          # Move the incremented value from $t0 to $a0 for printing
    li $v0, 1              # Load 1 into $v0 for the print integer system call
    syscall                # Make the system call to print the integer in $a0

    # Exit routine
    li $v0, 10          # Load 10 into $v0 for the exit system call
    syscall             # Make the system call to exit
