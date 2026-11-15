	.file	1 "20011109-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.globl	fail1
	.set	nomips16
	.ent	fail1
	.type	fail1, @function
fail1:
	.frame	$fp,16,$31		# vars= 0, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	sd	$fp,0($sp)
	move	$fp,$sp
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail1
	.size	fail1, .-fail1
	.align	2
	.globl	fail2
	.set	nomips16
	.ent	fail2
	.type	fail2, @function
fail2:
	.frame	$fp,16,$31		# vars= 0, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	sd	$fp,0($sp)
	move	$fp,$sp
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail2
	.size	fail2, .-fail2
	.align	2
	.globl	fail3
	.set	nomips16
	.ent	fail3
	.type	fail3, @function
fail3:
	.frame	$fp,16,$31		# vars= 0, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	sd	$fp,0($sp)
	move	$fp,$sp
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail3
	.size	fail3, .-fail3
	.align	2
	.globl	fail4
	.set	nomips16
	.ent	fail4
	.type	fail4, @function
fail4:
	.frame	$fp,16,$31		# vars= 0, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-16
	sd	$31,8($sp)
	sd	$fp,0($sp)
	move	$fp,$sp
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	fail4
	.size	fail4, .-fail4
	.align	2
	.globl	foo
	.set	nomips16
	.ent	foo
	.type	foo, @function
foo:
	.frame	$fp,32,$31		# vars= 16, regs= 2/0, args= 0, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-32
	sd	$31,24($sp)
	sd	$fp,16($sp)
	move	$fp,$sp
	sd	$4,0($fp)
	ld	$2,0($fp)
	li	$3,5			# 0x5
	bltc	$3,$2,$L6
	ld	$3,0($fp)
	li	$2,3			# 0x3
	bgec	$3,$2,$L7
	ld	$2,0($fp)
	li	$3,2			# 0x2
	bltc	$3,$2,$L6
	ld	$2,0($fp)
	bgtzc	$2,$L18
	ld	$3,0($fp)
	li	$2,-6			# 0xfffffffffffffffa
	beqc	$3,$2,$L9
	ld	$2,0($fp)
	beqzc	$2,$L10
	nop
	bc	$L6
$L9:
	balc	fail1
	bc	$L11
$L10:
	balc	fail2
	bc	$L11
$L7:
	balc	fail3
	bc	$L11
$L6:
	balc	fail4
	bc	$L11
$L18:
	nop
$L11:
	ld	$3,0($fp)
	li	$2,8			# 0x8
	beqc	$3,$2,$L12
	ld	$2,0($fp)
	li	$3,8			# 0x8
	bltc	$3,$2,$L13
	ld	$3,0($fp)
	li	$2,4			# 0x4
	beqc	$3,$2,$L14
	ld	$2,0($fp)
	li	$3,4			# 0x4
	bltc	$3,$2,$L13
	ld	$3,0($fp)
	li	$2,3			# 0x3
	beqc	$3,$2,$L19
	ld	$2,0($fp)
	li	$3,3			# 0x3
	bltc	$3,$2,$L13
	ld	$3,0($fp)
	li	$2,2			# 0x2
	beqc	$3,$2,$L12
	ld	$2,0($fp)
	li	$3,2			# 0x2
	bltc	$3,$2,$L13
	ld	$3,0($fp)
	li	$2,1			# 0x1
	beqc	$3,$2,$L19
	ld	$2,0($fp)
	li	$3,1			# 0x1
	bltc	$3,$2,$L13
	ld	$3,0($fp)
	li	$2,-3			# 0xfffffffffffffffd
	beqc	$3,$2,$L16
	ld	$2,0($fp)
	beqzc	$2,$L14
	nop
	bc	$L13
$L16:
	balc	fail1
	bc	$L17
$L14:
	balc	fail2
	bc	$L17
$L12:
	balc	mars_gcc_abort
$L13:
	balc	fail4
	bc	$L17
$L19:
	nop
$L17:
	nop
	move	$sp,$fp
	ld	$31,24($sp)
	ld	$fp,16($sp)
	daddiu	$sp,$sp,32
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
	li	$4,1			# 0x1
	balc	foo
	move	$4,$0
	balc	mars_gcc_exit
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
