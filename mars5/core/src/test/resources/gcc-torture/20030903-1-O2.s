	.file	1 "20030903-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	mars_gcc_main
	.set	nomips16
	.ent	mars_gcc_main
	.type	mars_gcc_main, @function
mars_gcc_main:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	lui	$2,%highest(test)
	lui	$4,%hi(test)
	daddiu	$2,$2,%higher(test)
	li	$3,3			# 0x3
	dsll	$2,$2,32
	daddu	$2,$2,$4
	lw	$2,%lo(test)($2)
	addiu	$2,$2,-1
	bgeuc	$3,$2,$L7
	move	$2,$0
	jrc	$31
$L7:
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
	.local	test
	.comm	test,4,4
