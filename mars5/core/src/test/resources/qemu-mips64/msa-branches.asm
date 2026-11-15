# Each branch adds 2 in its delay slot and 1 only on fall-through.
# Shift between branches so the stored result preserves each outcome.
ldi.b $w0, 0
ldi.b $w1, -1
insert.b $w1[0], $0
ldi.b $w2, -1
insert.w $w2[0], $0
daddiu $8, $0, 0
bz.v $w0, zero_vector
daddiu $8, $8, 2
daddiu $8, $8, 1
zero_vector:
dsll $8, $8, 2
bnz.v $w1, mixed_vector
daddiu $8, $8, 2
daddiu $8, $8, 1
mixed_vector:
dsll $8, $8, 2
bz.b $w1, zero_byte
daddiu $8, $8, 2
daddiu $8, $8, 1
zero_byte:
dsll $8, $8, 2
bnz.b $w1, not_all_bytes
daddiu $8, $8, 2
daddiu $8, $8, 1
not_all_bytes:
dsll $8, $8, 2
bz.h $w1, no_zero_halfword
daddiu $8, $8, 2
daddiu $8, $8, 1
no_zero_halfword:
dsll $8, $8, 2
bnz.h $w1, all_halfwords
daddiu $8, $8, 2
daddiu $8, $8, 1
all_halfwords:
dsll $8, $8, 2
bz.w $w2, zero_word
daddiu $8, $8, 2
daddiu $8, $8, 1
zero_word:
dsll $8, $8, 2
bnz.w $w2, not_all_words
daddiu $8, $8, 2
daddiu $8, $8, 1
not_all_words:
dsll $8, $8, 2
bz.d $w2, no_zero_doubleword
daddiu $8, $8, 2
daddiu $8, $8, 1
no_zero_doubleword:
dsll $8, $8, 2
bnz.d $w2, all_doublewords
daddiu $8, $8, 2
daddiu $8, $8, 1
all_doublewords:
sd $8, 0($5)
