# MARS Assembly Specification

## Basic Instructions

### Branch

* bc1f 1, label
* bc1t 1, label
* bc1f label
* bc1t label
* bgez $t1, label
* bgezal $t1, label
* bgtz $t1, label
* beq $t1, $t2, label
* blez $t1, label
* bltz $t1, label
* bltzal $t1, label
* bne $t1, $t2, label

### Compare

* c.eq.d $f2, $f4
* c.eq.d 1, $f2, $f4
* c.lt.d $f2, $f4
* c.lt.d 1, $f2, $f4
* c.le.d $f2, $f4
* c.le.d 1, $f2, $f4
* c.eq.s $f0, $f1
* c.eq.s 1, $f0, $f1
* c.lt.s $f0, $f1
* c.lt.s 1, $f0, $f1
* c.le.s $f0, $f1
* c.le.s 1, $f0, $f1

### Convert

* cvt.s.d $f1, $f2
* cvt.w.d $f1, $f2
* cvt.d.s $f2, $f1
* cvt.w.s $f0, $f1
* cvt.d.w $f2, $f1
* cvt.s.w $f0, $f1

### Jump

* j target
* jal target
* jalr $t1, $t2
* jalr $t1
* jr $t1

### Logic

* and $t1, $t2, $t3
* andi $t1, $t2, 100
* neg.d $f2, $f4
* neg.s $f0, $f1
* nor $t1, $t2, $t3
* or $t1, $t2, $t3
* ori $t1, $t2, 100
* sll $t1, $t2, 10
* sllv $t1, $t2, $t3
* sra $t1, $t2, 10
* srav $t1, $t2, $t3
* srl $t1, $t2, 10
* srlv $t1, $t2, $t3
* xor $t1, $t2, $t3
* xori $t1, $t2, 100

### Double Math

* abs.d $f2, $f4
* add.d $f2, $f4, $f6
* ceil.w.d $f1, $f2
* div.d $f2, $f4, $f6
* floor.w.d $f1, $f2
* mul.d $f2, $f4, $f6
* sqrt.d $f2, $f4
* sub.d $f2, $f4, $f6
* round.w.d $f1, $f2
* trunc.w.d $f1, $f2

### Integer Math

* add $t1, $t2, $t3
* addi $t1, $t2, -100
* addiu $t1, $t2, -100
* addu $t1, $t2, $t3
* clo $t1, $t2
* clz $t1, $t2
* div $t1, $t2
* divu $t1, $t2
* mult $t1, $t2
* madd $t1, $t2
* maddu $t1, $t2
* mul $t1, $t2, $t3
* msub $t1, $t2
* msubu $t1, $t2
* multu $t1, $t2
* sub $t1, $t2, $t3
* subu $t1, $t2, $t3

### Float Math

* abs.s $f0, $f1
* add.s $f0, $f1, $f3
* ceil.w.s $f0, $f1
* div.s $f0, $f1, $f3
* floor.w.s $f0, $f1
* mul.s $f0, $f0, $f3
* sqrt.s $f0, $f1
* sub.s $f0, $f1, $f3
* round.w.s $f0, $f1
* trunc.w.s $f0, $f1

### Load

* lb $t1, -100($t2)
* lbu $t1, -100($t2)
* ldc1 $f2, -100($t2)
* lh $t1, -100($t2)
* lhu $t1, -100($t2)
* ll $t1, -100($t2)
* lui $t1, 100
* lw $t1, -100($t2)
* lwc $f1, -100($t2)
* lwl $t1, -100($t2)
* lwr $t1, -100($t2)

### Store

* sb $t1, -100($t2)
* sc $t1, -100($t2)
* sdc $f2, -100($t2)
* sh $t1, -100($t2)
* sw $t1, -100($t2)
* swc1 $f1, -100($t2)
* swl $t1, -100($t2)
* swr $t1, -100($t2)

### Move

* movn $t1, $t2, $t3
* movz $t1, $t2, $t3
* mov.d $f2, $f4
* movf.d $f2, $f4, 1
* movt.d $f2, $f4, 1
* movf.d $f2, $f4
* movt.d $f2, $f4
* movn.d $f2, $f4, $t3
* movz.d $f2, $f4, $t3
* mov.s $f0, $f1
* movf.s $f0, $f1, 1
* movt.s $f0, $f1, 1
* movf.s $f0, $f1
* movt.s $f0, $f1
* movn.s $f0, $f1, $t3
* movz.s $f0, $f1, $t3
* mfc0 $t1, 8
* mfc1 $t1, $f1
* mfhi $t1
* mflo $t1
* movf $t1, $t2, 1
* movt $t1, $t2, 1
* movf $t1, $t2
* movt $t1, $t2
* mtc0 $t1, 8
* mtc1 $t1, $f1
* mthi $t1
* mtli $t1

### Set

* slt $t1, $t2, $t3
* slti $t1, $t2, -100
* sltiu $t1, $t2, -100
* sltu $t1, $t2, $t3

### System

* break 100
* break
* syscall
* nop

### Traps

* eret
* teq $t1, $t2
* teqi $t1, -100
* tge $t1, $t2
* tgei $t1, -100
* tgeiu $t1, -100
* tgeu $t1, $t2
* tlt $t1, $t2
* tlti $t1, -100
* tltiu $t1, -100
* tltu $t1, $t2
* tne $t1, $t2
* tnei $t1, -100

## Extended/"Pseudo" Instructions

**NOTE:** These instructions are not logically grouped like the previous items. They are automatically expanded by the
MARS extended instruction parser.

* not $t1, $t2
* add $t1, $t2, -100
* add $t1, $t2, 100000
* addu $t1, $t2, 100000
* addi $t1, $t2, 100000
* addiu $t1, $t2, 100000
* sub $t1, $t2, -100
* sub $t1, $t2, 100000
* subu $t1, $t2, 100000
* subi $t1, $t2, -100
* subi $t1, $t2, 100000
* subiu $t1, $t2, 100000
* andi $t1, $t2, 100000
* ori $t1, $t2, 100000
* xori $t1, $t2, 100000
* and $t1, $t2, 100
* or $t1, $t2, 100
* xor $t1, $t2, 100
* and $t1, 100
* or $t1, 100
* xor $t1, 100
* andi $t1, 100
* ori $t1, 100
* xori $t1, 100
* andi $t1, 100000
* ori $t1, 100000
* xori $t1, 100000
* seq $t1, $t2, $t3
* seq $t1, $t2, -100
* seq $t1, $t2, 100000
* sne $t1, $t2, $t3
* sne $t1, $t2, -100
* sne $t1, $t2, 100000
* sge $t1, $t2, $t3
* sge $t1, $t2, -100
* sge $t1, $t2, 100000
* sgeu $t1, $t2, $t3
* sgeu $t1, $t2, -100
* sgeu $t1, $t2, 100000
* sgt $t1, $t2, $t3
* sgt $t1, $t2, -100
* sgt $t1, $t2, 100000
* sgtu $t1, $t2, $t3
* sgtu $t1, $t2, -100
* sgtu $t1, $t2, 100000
* sle $t1, $t2, $t3
* sle $t1, $t2, -100
* sle $t1, $t2, 100000
* sleu $t1, $t2, $t3
* sleu $t1, $t2, -100
* sleu $t1, $t2, 100000
* move $t1, $t2
* abs $t1, $t2
* neg $t1, $t2
* negu $t1, $t2
* b label
* beqz $t1, label
* bnez $t1, label
* beq $t1, -100, label
* beq $t1, 100000, label
* bne $t1, -100, label
* bne $t1, 100000, label
* bge $t1, $t2, label
* bge $t1, -100, label
* bge $t1, 100000, label
* bgeu $t1, $t2, label
* bgeu $t1, -100, label
* bgeu $t1, 100000, label
* bgt $t1, $t2, label
* bgt $t1, -100, label
* bgt $t1, 100000, label
* bgtu $t1, $t2, label
* bgtu $t1, -100, label
* bgtu $t1, 100000, label
* ble $t1, $t2, label
* ble $t1, -100, label
* ble $t1, 100000, label
* bleu $t1, $t2, label
* bleu $t1, -100, label
* bleu $t1, 100000, label
* blt $t1, $t2, label
* blt $t1, -100, label
* blt $t1, 100000, label
* bltu $t1, $t2, label
* bltu $t1, -100, label
* bltu $t1, 100000, label
* rol $t1, $t2, $t3
* rol $t1, $t2, 10
* ror $t1, $t2, $t3
* ror $t1, $t2, 10
* mfc1.d $t1, $f2
* mtc1.d $t1, $f2
* mul $t1, $t2, -100
* mul $t1, $t2, 100000
* mulu $t1, $t2, $t3
* mulu $t1, $t2, -100
* mulu $t1, $t2, 100000
* mulo $t1, $t2, $t3
* mulo $t1, $t2, -100
* mulo $t1, $t2, 100000
* mulou $t1, $t2, $t3
* mulou $t1, $t2, -100
* mulou $t1, $t2, 100000
* div $t1, $t2, $t3
* div $t1, $t2, -100
* div $t1, $t2, 100000
* divu $t1, $t2, $t3
* divu $t1, $t2, -100
* divu $t1, $t2, 100000
* rem $t1, $t2, $t3
* rem $t1, $t2, -100
* rem $t1, $t2, 100000
* remu $t1, $t2, $t3
* remu $t1, $t2, -100
* remu $t1, $t2, 100000
* li $t1, -100
* li $t1, 100
* li $t1, 100000
* la $t1, ($t2)
* la $t1, -100
* la $t1, 100
* la $t1, 100000
* la $t1, 100($t2)
* la $t1, 100000($t2)
* la $t1, label
* la $t1, label($t2)
* la $t1, label+100000
* la $t1, label+100000($t2)
* lw $t1, ($t2)
* lw $t1, -100
* lw $t1, 100
* lw $t1, 100000
* lw $t1, 100($t2)
* lw $t1, 100000($t2)
* lw $t1, label
* lw $t1, label($t2)
* lw $t1, label+100000
* lw $t1, label+100000($t2)
* sw $t1, ($t2)
* sw $t1, -100
* sw $t1, 100
* sw $t1, 100000
* sw $t1, 100($t2)
* sw $t1, 100000($t2)
* sw $t1, label
* sw $t1, label($t2)
* sw $t1, label+100000
* sw $t1, label+100000($t2)
* lh $t1, ($t2)
* lh $t1, -100
* lh $t1, 100
* lh $t1, 100000
* lh $t1, 100($t2)
* lh $t1, 100000($t2)
* lh $t1, label
* lh $t1, label($t2)
* lh $t1, label+100000
* lh $t1, label+100000($t2)
* sh $t1, ($t2)
* sh $t1, -100
* sh $t1, 100
* sh $t1, 100000
* sh $t1, 100($t2)
* sh $t1, 100000($t2)
* sh $t1, label
* sh $t1, label($t2)
* sh $t1, label+100000
* sh $t1, label+100000($t2)
* lb $t1, ($t2)
* lb $t1, -100
* lb $t1, 100
* lb $t1, 100000
* lb $t1, 100($t2)
* lb $t1, 100000($t2)
* lb $t1, label
* lb $t1, label($t2)
* lb $t1, label+100000
* lb $t1, label+100000($t2)
* sb $t1, ($t2)
* sb $t1, -100
* sb $t1, 100
* sb $t1, 100000
* sb $t1, 100($t2)
* sb $t1, 100000($t2)
* sb $t1, label
* sb $t1, label($t2)
* sb $t1, label+100000
* sb $t1, label+100000($t2)
* lhu $t1, ($t2)
* lhu $t1, -100
* lhu $t1, 100
* lhu $t1, 100000
* lhu $t1, 100($t2)
* lhu $t1, 100000($t2)
* lhu $t1, label
* lhu $t1, label($t2)
* lhu $t1, label+100000
* lhu $t1, label+100000($t2)
* lbu $t1, ($t2)
* lbu $t1, -100
* lbu $t1, 100
* lbu $t1, 100000
* lbu $t1, 100($t2)
* lbu $t1, 100000($t2)
* lbu $t1, label
* lbu $t1, label($t2)
* lbu $t1, label+100000
* lbu $t1, label+100000($t2)
* lwl $t1, ($t2)
* lwl $t1, -100
* lwl $t1, 100
* lwl $t1, 100000
* lwl $t1, 100($t2)
* lwl $t1, 100000($t2)
* lwl $t1, label
* lwl $t1, label($t2)
* lwl $t1, label+100000
* lwl $t1, label+100000($t2)
* swl $t1, ($t2)
* swl $t1, -100
* swl $t1, 100
* swl $t1, 100000
* swl $t1, 100($t2)
* swl $t1, 100000($t2)
* swl $t1, label
* swl $t1, label($t2)
* swl $t1, label+100000
* swl $t1, label+100000($t2)
* lwr $t1, ($t2)
* lwr $t1, -100
* lwr $t1, 100
* lwr $t1, 100000
* lwr $t1, 100($t2)
* lwr $t1, 100000($t2)
* lwr $t1, label
* lwr $t1, label($t2)
* lwr $t1, label+100000
* lwr $t1, label+100000($t2)
* swr $t1, ($t2)
* swr $t1, -100
* swr $t1, 100
* swr $t1, 100000
* swr $t1, 100($t2)
* swr $t1, 100000($t2)
* swr $t1, label
* swr $t1, label($t2)
* swr $t1, label+100000
* swr $t1, label+100000($t2)
* ll $t1, ($t2)
* ll $t1, -100
* ll $t1, 100
* ll $t1, 100000
* ll $t1, 100($t2)
* ll $t1, 100000($t2)
* ll $t1, label
* ll $t1, label($t2)
* ll $t1, label+100000
* ll $t1, label+100000($t2)
* sc $t1, ($t2)
* sc $t1, -100
* sc $t1, 100
* sc $t1, 100000
* sc $t1, 100($t2)
* sc $t1, 100000($t2)
* sc $t1, label
* sc $t1, label($t2)
* sc $t1, label+100000
* sc $t1, label+100000($t2)
* ulw $t1, -100($t2)
* ulh $t1, -100($t2)
* ulhu $t1, -100($t2)
* ld $t1, -100($t2)
* usw $t1, -100($t2)
* ush $t1, -100($t2)
* sd $t1, -100($t2)
* ulw $t1, 100000
* ulw $t1, label
* ulw $t1, label+100000
* ulw $t1, ($t2)
* ulw $t1, 100000($t2)
* ulw $t1, label($t2)
* ulw $t1, label+100000($t2)
* ulh $t1, 100000
* ulh $t1, label
* ulh $t1, label+100000
* ulh $t1, ($t2)
* ulh $t1, 100000($t2)
* ulh $t1, label($t2)
* ulh $t1, label+100000($t2)
* ulhu $t1, 100000
* ulhu $t1, label
* ulhu $t1, label+100000
* ulhu $t1, ($t2)
* ulhu $t1, 100000($t2)
* ulhu $t1, label($t2)
* ulhu $t1, label+100000($t2)
* ld $t1, 100000
* ld $t1, label
* ld $t1, label+100000
* ld $t1, ($t2)
* ld $t1, 100000($t2)
* ld $t1, label($t2)
* ld $t1, label+100000($t2)
* usw $t1, 100000
* usw $t1, label
* usw $t1, label+100000
* usw $t1, ($t2)
* usw $t1, 100000($t2)
* usw $t1, label($t2)
* usw $t1, label+100000($t2)
* ush $t1, 100000
* ush $t1, label
* ush $t1, label+100000
* ush $t1, ($t2)
* ush $t1, 100000($t2)
* ush $t1, label($t2)
* ush $t1, label+100000($t2)
* sd $t1, 100000
* sd $t1, label
* sd $t1, label+100000
* sd $t1, ($t2)
* sd $t1, 100000($t2)
* sd $t1, label($t2)
* sd $t1, label+100000($t2)
* lwc1 $f1, ($t2)
* lwc1 $f1, -100
* lwc1 $f1, 100000
* lwc1 $f1, 100000($t2)
* lwc1 $f1, label
* lwc1 $f1, label($t2)
* lwc1 $f1, label+100000
* lwc1 $f1, label+100000($t2)
* ldc1 $f2, ($t2)
* ldc1 $f2, -100
* ldc1 $f2, 100000
* ldc1 $f2, 100000($t2)
* ldc1 $f2, label
* ldc1 $f2, label($t2)
* ldc1 $f2, label+100000
* ldc1 $f2, label+100000($t2)
* swc1 $f1, ($t2)
* swc1 $f1, -100
* swc1 $f1, 100000
* swc1 $f1, 100000($t2)
* swc1 $f1, label
* swc1 $f1, label($t2)
* swc1 $f1, label+100000
* swc1 $f1, label+100000($t2)
* sdc1 $f2, ($t2)
* sdc1 $f2, -100
* sdc1 $f2, 100000
* sdc1 $f2, 100000($t2)
* sdc1 $f2, label
* sdc1 $f2, label($t2)
* sdc1 $f2, label+100000
* sdc1 $f2, label+100000($t2)
* l.s $f1, ($t2)
* l.s $f1, -100
* l.s $f1, 100000
* l.s $f1, 100000($t2)
* l.s $f1, label
* l.s $f1, label($t2)
* l.s $f1, label+100000
* l.s $f1, label+100000($t2)
* s.s $f1, ($t2)
* s.s $f1, -100
* s.s $f1, 100000
* s.s $f1, 100000($t2)
* s.s $f1, label
* s.s $f1, label($t2)
* s.s $f1, label+100000
* s.s $f1, label+100000($t2)
* l.d $f2, ($t2)
* l.d $f2, -100
* l.d $f2, 100000
* l.d $f2, 100000($t2)
* l.d $f2, label
* l.d $f2, label($t2)
* l.d $f2, label+100000
* l.d $f2, label+100000($t2)
* s.d $f2, ($t2)
* s.d $f2, -100
* s.d $f2, 100000
* s.d $f2, 100000($t2)
* s.d $f2, label
* s.d $f2, label($t2)
* s.d $f2, label+100000
* s.d $f2, label+100000($t2)

Directives
========

* .data
* .text
* .word
* .ascii
* .asciiz
* .byte
* .align
* .half
* .space
* .double
* .float
* .extern
* .kdata
* .ktext
* .globl
* .set
* .eqv
* .macro
* .end_macro
* .include