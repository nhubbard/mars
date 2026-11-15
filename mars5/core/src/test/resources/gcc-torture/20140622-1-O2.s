	.file	1 "20140622-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.align	3
	.globl	test
	.set	nomips16
	.ent	test
	.type	test, @function
test:
	.frame	$sp,0,$31		# vars= 0, regs= 0/0, args= 0, gp= 0
	.mask	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	lui	$2,%highest(p)
	lui	$3,%hi(p)
	daddiu	$2,$2,%higher(p)
	dsll	$2,$2,32
	daddu	$2,$2,$3
	lw	$2,%lo(p)($2)
	addu	$4,$2,$4
	dext	$2,$2,0,32
	dext	$4,$4,0,32
	jr	$31
	dsubu	$2,$4,$2

	.set	macro
	.set	reorder
	.end	test
	.size	test, .-test
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
	lui	$6,%highest(p)
	lui	$2,%hi(p)
	daddiu	$6,$6,%higher(p)
	daddiu	$sp,$sp,-16
	dsll	$6,$6,32
	move	$4,$0
	daddu	$6,$6,$2
	li	$2,-2			# 0xfffffffffffffffe
	sw	$2,%lo(p)($6)
	sd	$31,8($sp)
	balc	test
	bnezc	$2,$L5
	li	$4,1			# 0x1
	balc	test
	li	$3,1			# 0x1
	bnec	$2,$3,$L5
	li	$4,2			# 0x2
	balc	test
	move	$5,$2
	li	$2,-2147483648			# 0xffffffff80000000
	daddiu	$2,$2,1
	dsll	$2,$2,1
	bnec	$5,$2,$L5
	li	$2,-1			# 0xffffffffffffffff
	move	$4,$0
	sw	$2,%lo(p)($6)
	balc	test
	bnezc	$2,$L5
	li	$4,1			# 0x1
	balc	test
	daddiu	$3,$5,-1
	bnec	$2,$3,$L5
	li	$4,2			# 0x2
	balc	test
	bnec	$2,$5,$L5
	ld	$31,8($sp)
	move	$2,$0
	jr	$31
	daddiu	$sp,$sp,16

$L5:
	balc	mars_gcc_abort
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
	.globl	p
	.section	.bss,"aw",@nobits
	.align	2
	.type	p, @object
	.size	p, 4
p:
	.space	4
