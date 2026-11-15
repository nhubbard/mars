	.file	1 "pr98727.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.globl	foo
	.set	nomips16
	.ent	foo
	.type	foo, @function
foo:
	.frame	$fp,48,$31		# vars= 32, regs= 1/0, args= 0, gp= 0
	.mask	0x40000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-48
	sd	$fp,40($sp)
	move	$fp,$sp
	sd	$4,16($fp)
	sd	$5,24($fp)
	ld	$3,16($fp)
	ld	$2,24($fp)
	dmul	$2,$3,$2
	sd	$2,0($fp)
	ld	$4,0($fp)
	ld	$3,24($fp)
	ddiv	$2,$4,$3
	teq	$3,$0,7
	ld	$3,16($fp)
	beqc	$3,$2,$L2
	li	$2,-1			# 0xffffffffffffffff
	bc	$L3
$L2:
	ld	$2,0($fp)
$L3:
	move	$sp,$fp
	ld	$fp,40($sp)
	daddiu	$sp,$sp,48
	jrc	$31
	.set	macro
	.set	reorder
	.end	foo
	.size	foo, .-foo
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
	li	$5,24			# 0x18
	li	$4,4			# 0x4
	balc	foo
	move	$3,$2
	li	$2,96			# 0x60
	bnec	$3,$2,$L5
	li	$5,126			# 0x7e
	li	$4,124			# 0x7c
	balc	foo
	move	$3,$2
	li	$2,15624			# 0x3d08
	bnec	$3,$2,$L5
	li	$5,17			# 0x11
	li	$2,1			# 0x1
	dsll	$2,$2,59
	daddiu	$4,$2,-1
	balc	foo
	move	$3,$2
	li	$2,-1			# 0xffffffffffffffff
	beqc	$3,$2,$L6
	nop
$L5:
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
