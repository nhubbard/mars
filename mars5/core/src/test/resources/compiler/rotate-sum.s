	.section	.mdebug.abi64,"",@progbits
	.nan	2008
	.text
	.file	"rotate-sum.c"
	.globl	rotate_sum                      # -- Begin function rotate_sum
	.p2align	3
	.type	rotate_sum,@function
	.set	nomicromips
	.set	nomips16
	.ent	rotate_sum
rotate_sum:                             # @rotate_sum
	.frame	$sp,0,$ra
	.mask 	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	.set	noat
# %bb.0:
	beqzc	$5, .LBB0_4
# %bb.1:
	dext $1, $5, 0, 32
	dsll	$1, $1, 3
	daddu	$3, $4, $1
	daddiu	$2, $zero, 0
.LBB0_2:                                # =>This Inner Loop Header: Depth=1
	ld	$1, 0($4)
	drotr	$1, $1, 57
	daddiu	$4, $4, 8
	bne	$4, $3, .LBB0_2
	daddu	$2, $1, $2
# %bb.3:
	jrc	$ra
.LBB0_4:
	jr	$ra
	daddiu	$2, $zero, 0
	.set	at
	.set	macro
	.set	reorder
	.end	rotate_sum
.Lfunc_end0:
	.size	rotate_sum, .Lfunc_end0-rotate_sum
                                        # -- End function
	.ident	"Homebrew clang version 23.1.5"
	.section	".note.GNU-stack","",@progbits
	.addrsig
	.text
