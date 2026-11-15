	.file	1 "20010518-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	add
	.set	nomips16
	.ent	add
	.type	add, @function
add:
	.frame	$sp,0,$31		# vars= 0, regs= 0/0, args= 0, gp= 0
	.mask	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	addu	$4,$4,$5
	lw	$2,4($sp)
	addu	$4,$4,$6
	lw	$3,12($sp)
	addu	$4,$4,$7
	addu	$4,$4,$8
	addu	$4,$4,$9
	addu	$4,$4,$10
	addu	$4,$4,$11
	addu	$2,$4,$2
	addu	$2,$2,$3
	lw	$3,20($sp)
	addu	$2,$2,$3
	lw	$3,28($sp)
	addu	$2,$2,$3
	lw	$3,36($sp)
	jr	$31
	addu	$2,$2,$3

	.set	macro
	.set	reorder
	.end	add
	.size	add, .-add
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
