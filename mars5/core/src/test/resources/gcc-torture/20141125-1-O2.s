	.file	1 "20141125-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	f
	.set	nomips16
	.ent	f
	.type	f, @function
f:
	.frame	$sp,0,$31		# vars= 0, regs= 0/0, args= 0, gp= 0
	.mask	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	dext	$4,$4,0,58
	bnez	$4,$L5
	li	$2,1			# 0x1

	li	$2,1024			# 0x400
$L5:
	jrc	$31
	.set	macro
	.set	reorder
	.end	f
	.size	f, .-f
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
	li	$4,9437184			# 0x900000
	daddiu	$sp,$sp,-16
	daddiu	$4,$4,28347
	li	$3,1			# 0x1
	dsll	$4,$4,39
	sd	$31,8($sp)
	balc	f
	bnec	$3,$2,$L8
	li	$4,-1			# 0xffffffffffffffff
	li	$3,1024			# 0x400
	dsll	$4,$4,58
	balc	f
	bnec	$3,$2,$L8
	ld	$31,8($sp)
	move	$2,$0
	jr	$31
	daddiu	$sp,$sp,16

$L8:
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
