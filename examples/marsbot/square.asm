# Use the default memory layout and enable "Connect MarsBot from launch".
# After the run, open View > Tool Windows > MARS Tools > MarsBot.
.eqv BOT_HEADING 0xffff8010
.eqv BOT_PEN     0xffff8020
.eqv BOT_X       0xffff8030
.eqv BOT_Y       0xffff8040
.eqv BOT_MOVE    0xffff8050

.macro move_to (%heading, %port, %target, %wait)
    li $t0, %heading
    sw $t0, BOT_HEADING
    li $t0, 1
    sw $t0, BOT_MOVE
    li $t0, %target
poll:
    lw $t1, %port
    %wait $t1, $t0, poll
    nop
    sw $zero, BOT_MOVE
.end_macro

.data
done: .asciiz "Square complete. Open the MarsBot tool to inspect its trail.\n"

.text
.globl main
main:
    # Travel to the start with the pen lifted.
    move_to (90, BOT_X, 96, blt)
    move_to (180, BOT_Y, 96, blt)

    li $t0, 1
    sw $t0, BOT_PEN
    move_to (90, BOT_X, 416, blt)
    move_to (180, BOT_Y, 416, blt)
    move_to (270, BOT_X, 96, bgt)
    move_to (0, BOT_Y, 96, bgt)
    sw $zero, BOT_PEN

    li $v0, 4
    la $a0, done
    syscall
    li $v0, 10
    syscall
