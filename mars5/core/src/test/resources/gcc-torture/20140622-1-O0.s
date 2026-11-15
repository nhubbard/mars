	.file	1 "20140622-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.globl	p
	.section	.bss,"aw",@nobits
	.align	2
	.type	p, @object
	.size	p, 4
p:
	.space	4
	.text
	.align	2
	.globl	test
	.set	nomips16
	.ent	test
	.type	test, @function
test:
	.frame	$fp,32,$31		# vars= 16, regs= 1/0, args= 0, gp= 0
	.mask	0x40000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-32
	sd	$fp,24($sp)
	move	$fp,$sp
	move	$2,$4
	sll	$2,$2,0
	sw	$2,0($fp)
	lui	$2,%highest(p)
	daddiu	$2,$2,%higher(p)
	dsll	$2,$2,16
	daddiu	$2,$2,%hi(p)
	dsll	$2,$2,16
	lw	$2,%lo(p)($2)
	lw	$3,0($fp)
	addu	$2,$3,$2
	dext	$3,$2,0,32
	lui	$2,%highest(p)
	daddiu	$2,$2,%higher(p)
	dsll	$2,$2,16
	daddiu	$2,$2,%hi(p)
	dsll	$2,$2,16
	lw	$2,%lo(p)($2)
	dext	$2,$2,0,32
	dsubu	$2,$3,$2
	move	$sp,$fp
	ld	$fp,24($sp)
	daddiu	$sp,$sp,32
	jrc	$31
	.set	macro
	.set	reorder
	.end	test
	.size	test, .-test
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
	lui	$2,%highest(p)
	daddiu	$2,$2,%higher(p)
	dsll	$2,$2,16
	daddiu	$2,$2,%hi(p)
	dsll	$2,$2,16
	li	$3,-2			# 0xfffffffffffffffe
	sw	$3,%lo(p)($2)
	move	$4,$0
	balc	test
	beqzc	$2,$L4
	nop
	balc	mars_gcc_abort
$L4:
	li	$4,1			# 0x1
	balc	test
	move	$3,$2
	li	$2,1			# 0x1
	beqc	$3,$2,$L5
	nop
	balc	mars_gcc_abort
$L5:
	li	$4,2			# 0x2
	balc	test
	move	$3,$2
	li	$2,-2147483648			# 0xffffffff80000000
	ori	$2,$2,0x1
	dsll	$2,$2,1
	beqc	$3,$2,$L6
	nop
	balc	mars_gcc_abort
$L6:
	lui	$2,%highest(p)
	daddiu	$2,$2,%higher(p)
	dsll	$2,$2,16
	daddiu	$2,$2,%hi(p)
	dsll	$2,$2,16
	li	$3,-1			# 0xffffffffffffffff
	sw	$3,%lo(p)($2)
	move	$4,$0
	balc	test
	beqzc	$2,$L7
	nop
	balc	mars_gcc_abort
$L7:
	li	$4,1			# 0x1
	balc	test
	move	$3,$2
	li	$2,-1			# 0xffffffffffffffff
	dsll	$2,$2,32
	ori	$2,$2,0x1
	beqc	$3,$2,$L8
	nop
	balc	mars_gcc_abort
$L8:
	li	$4,2			# 0x2
	balc	test
	move	$3,$2
	li	$2,-2147483648			# 0xffffffff80000000
	ori	$2,$2,0x1
	dsll	$2,$2,1
	beqc	$3,$2,$L9
	nop
	balc	mars_gcc_abort
$L9:
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
