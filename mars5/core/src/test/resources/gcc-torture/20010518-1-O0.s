	.file	1 "20010518-1.c"
	.section .mdebug.abi64
	.previous
	.nan	2008
	.text
	.align	2
	.globl	add
	.set	nomips16
	.ent	add
	.type	add, @function
add:
	.frame	$fp,48,$31		# vars= 32, regs= 1/0, args= 0, gp= 0
	.mask	0x40000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-48
	sd	$fp,40($sp)
	move	$fp,$sp
	move	$14,$4
	move	$13,$5
	move	$12,$6
	move	$6,$7
	move	$5,$8
	move	$4,$9
	move	$3,$10
	move	$2,$11
	sll	$7,$14,0
	sw	$7,0($fp)
	sll	$7,$13,0
	sw	$7,4($fp)
	sll	$7,$12,0
	sw	$7,8($fp)
	sll	$6,$6,0
	sw	$6,12($fp)
	sll	$5,$5,0
	sw	$5,16($fp)
	sll	$4,$4,0
	sw	$4,20($fp)
	sll	$3,$3,0
	sw	$3,24($fp)
	sll	$2,$2,0
	sw	$2,28($fp)
	lw	$3,0($fp)
	lw	$2,4($fp)
	addu	$2,$3,$2
	lw	$3,8($fp)
	addu	$2,$3,$2
	lw	$3,12($fp)
	addu	$2,$3,$2
	lw	$3,16($fp)
	addu	$2,$3,$2
	lw	$3,20($fp)
	addu	$2,$3,$2
	lw	$3,24($fp)
	addu	$2,$3,$2
	lw	$3,28($fp)
	addu	$2,$3,$2
	lw	$3,52($fp)
	addu	$2,$3,$2
	lw	$3,60($fp)
	addu	$2,$3,$2
	lw	$3,68($fp)
	addu	$2,$3,$2
	lw	$3,76($fp)
	addu	$2,$3,$2
	lw	$3,84($fp)
	addu	$2,$3,$2
	move	$sp,$fp
	ld	$fp,40($sp)
	daddiu	$sp,$sp,48
	jrc	$31
	.set	macro
	.set	reorder
	.end	add
	.size	add, .-add
	.align	2
	.globl	mars_gcc_main
	.set	nomips16
	.ent	mars_gcc_main
	.type	mars_gcc_main, @function
mars_gcc_main:
	.frame	$fp,64,$31		# vars= 0, regs= 2/0, args= 48, gp= 0
	.mask	0xc0000000,-8
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	daddiu	$sp,$sp,-64
	sd	$31,56($sp)
	sd	$fp,48($sp)
	move	$fp,$sp
	li	$2,13			# 0xd
	sd	$2,32($sp)
	li	$2,12			# 0xc
	sd	$2,24($sp)
	li	$2,11			# 0xb
	sd	$2,16($sp)
	li	$2,10			# 0xa
	sd	$2,8($sp)
	li	$2,9			# 0x9
	sd	$2,0($sp)
	li	$11,8			# 0x8
	li	$10,7			# 0x7
	li	$9,6			# 0x6
	li	$8,5			# 0x5
	li	$7,4			# 0x4
	li	$6,3			# 0x3
	li	$5,2			# 0x2
	li	$4,1			# 0x1
	balc	add
	move	$3,$2
	li	$2,91			# 0x5b
	beqc	$3,$2,$L4
	nop
	balc	mars_gcc_abort
$L4:
	move	$4,$0
	balc	mars_gcc_exit
	.set	macro
	.set	reorder
	.end	mars_gcc_main
	.size	mars_gcc_main, .-mars_gcc_main
