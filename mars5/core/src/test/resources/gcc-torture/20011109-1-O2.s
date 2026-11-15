	.file	1 "20011109-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	fail1
	.set	nomips16
	.ent	fail1
	.type	fail1, @function
fail1:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail1
	.size	fail1, .-fail1
	.align	2
	.align	3
	.globl	fail2
	.set	nomips16
	.ent	fail2
	.type	fail2, @function
fail2:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail2
	.size	fail2, .-fail2
	.align	2
	.align	3
	.globl	fail3
	.set	nomips16
	.ent	fail3
	.type	fail3, @function
fail3:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail3
	.size	fail3, .-fail3
	.align	2
	.align	3
	.globl	fail4
	.set	nomips16
	.ent	fail4
	.type	fail4, @function
fail4:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail4
	.size	fail4, .-fail4
	.align	2
	.align	3
	.globl	foo
	.set	nomips16
	.ent	foo
	.type	foo, @function
foo:
	.frame	$sp,16,$31		# vars= 0, regs= 1/0, args= 0, gp= 0
	.mask	0x80000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	li	$3,1			# 0x1
	daddiu	$2,$4,-1
	bltuc	$3,$2,$L12
	li	$2,2			# 0x2
	beqc	$4,$2,$L12
	nop
	jrc	$31
$L12:
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	balc	mars_gcc_abort
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
	move	$4,$0
	sd	$31,8($sp)
	balc	mars_gcc_exit
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
