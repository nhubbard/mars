	.file	1 "20141125-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.globl	f
	.set	nomips16
	.ent	f
	.type	f, @function
f:
	.frame	$fp,32,$31		# vars= 16, regs= 1/0, args= 0, gp= 0
	.mask	0x40000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-32
	sd	$fp,24($sp)
	move	$fp,$sp
	sd	$4,0($fp)
	ld	$2,0($fp)
	dext	$2,$2,0,58
	beqzc	$2,$L2
	li	$2,1			# 0x1
	bc	$L3
$L2:
	li	$2,1024			# 0x400
$L3:
	move	$sp,$fp
	ld	$fp,24($sp)
	daddiu	$sp,$sp,32
	jrc	$31
	.set	macro
	.set	reorder
	.end	f
	.size	f, .-f
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
	li	$2,9437184			# 0x900000
	ori	$2,$2,0x6ebb
	dsll	$4,$2,39
	balc	f
	move	$3,$2
	li	$2,1			# 0x1
	beqc	$3,$2,$L5
	nop
	balc	mars_gcc_abort
$L5:
	li	$2,-1			# 0xffffffffffffffff
	dsll	$4,$2,58
	balc	f
	move	$3,$2
	li	$2,1024			# 0x400
	beqc	$3,$2,$L6
	nop
	balc	mars_gcc_abort
$L6:
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
