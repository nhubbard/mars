	.file	1 "pr98727.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	foo
	.set	nomips16
	.ent	foo
	.type	foo, @function
foo:
	.frame	$sp,0,$31		# vars= 0, regs= 0/0, args= 0, gp= 0
	.mask	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	dmul	$2,$4,$5
	ddiv	$3,$2,$5
	teq	$5,$0,7
	bnec	$3,$4,$L5
	nop
	jrc	$31
	.align	3
$L5:
	jr	$31
	li	$2,-1			# 0xffffffffffffffff

	.set	macro
	.set	reorder
	.end	foo
	.size	foo, .-foo
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
	daddiu	$sp,$sp,-16
	li	$5,24			# 0x18
	li	$4,4			# 0x4
	sd	$31,8($sp)
	balc	foo
	li	$3,96			# 0x60
	bnec	$2,$3,$L8
	li	$5,126			# 0x7e
	li	$4,124			# 0x7c
	balc	foo
	li	$3,15624			# 0x3d08
	bnec	$2,$3,$L8
	li	$4,1			# 0x1
	li	$5,17			# 0x11
	dsll	$4,$4,59
	daddiu	$4,$4,-1
	balc	foo
	li	$3,-1			# 0xffffffffffffffff
	bnec	$2,$3,$L8
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
