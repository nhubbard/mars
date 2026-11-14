	.section	.mdebug.abi64,"",@progbits
	.nan	2008
	.text
	.file	"lookup.c"
	.globl	lookup                          # -- Begin function lookup
	.p2align	3
	.type	lookup,@function
	.set	nomicromips
	.set	nomips16
	.ent	lookup
lookup:                                 # @lookup
	.frame	$sp,0,$ra
	.mask 	0x00000000,0
	.fmask	0x00000000,0
	.set	noreorder
	.set	nomacro
	.set	noat
# %bb.0:
	andi	$1, $4, 1
	dsll	$1, $1, 3
	lui	$2, %highest(table)
	daddiu	$2, $2, %higher(table)
	dsll	$2, $2, 16
	daddiu	$2, $2, %hi(table)
	dsll	$2, $2, 16
	daddiu	$2, $2, %lo(table)
	daddu	$1, $2, $1
	ld	$1, 0($1)
	jr	$ra
	daddiu	$2, $1, 1
	.set	at
	.set	macro
	.set	reorder
	.end	lookup
.Lfunc_end0:
	.size	lookup, .Lfunc_end0-lookup
                                        # -- End function
	.type	table,@object                   # @table
	.section	.rodata.cst16,"aM",@progbits,16
	.p2align	3, 0x0
table:
	.8byte	81985529216486895               # 0x123456789abcdef
	.8byte	-81985529216486896              # 0xfedcba9876543210
	.size	table, 16

	.ident	"Homebrew clang version 23.1.5"
	.section	".note.GNU-stack","",@progbits
	.addrsig
	.text
