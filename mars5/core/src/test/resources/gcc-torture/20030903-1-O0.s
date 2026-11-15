	.file	1 "20030903-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.local	test
	.comm	test,4,4
	.align	2
	.globl	mars_gcc_main
	.set	nomips16
	.ent	mars_gcc_main
	.type	mars_gcc_main, @function
mars_gcc_main:
	.frame	$fp,16,$31		# vars= 0, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	sd	$fp,0($sp)
	move	$fp,$sp
	lui	$2,%highest(test)
	daddiu	$2,$2,%higher(test)
	dsll	$2,$2,16
	daddiu	$2,$2,%hi(test)
	dsll	$2,$2,16
	lw	$2,%lo(test)($2)
	li	$3,4			# 0x4
	beqc	$2,$3,$L2
	li	$3,4			# 0x4
	bltuc	$3,$2,$L3
	li	$3,3			# 0x3
	beqc	$2,$3,$L4
	li	$3,3			# 0x3
	bltuc	$3,$2,$L3
	li	$3,1			# 0x1
	beqc	$2,$3,$L5
	li	$3,2			# 0x2
	beqc	$2,$3,$L6
	nop
	bc	$L3
$L5:
	li	$4,1			# 0x1
	balc	y
	bc	$L3
$L6:
	li	$4,2			# 0x2
	balc	y
	bc	$L3
$L4:
	li	$4,3			# 0x3
	balc	y
	bc	$L3
$L2:
	li	$4,4			# 0x4
	balc	y
	nop
$L3:
	move	$2,$0
	move	$sp,$fp
	ld	$31,8($sp)
	ld	$fp,0($sp)
	daddiu	$sp,$sp,16
	jrc	$31
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
	.align	2
	.set	nomips16
	.ent	y
	.type	y, @function
y:
	.frame	$fp,32,$31		# vars= 16, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-32
	sd	$31,24($sp)
	sd	$fp,16($sp)
	move	$fp,$sp
	move	$2,$4
	sll	$2,$2,0
	sw	$2,0($fp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	y
	.size	y, .-y
