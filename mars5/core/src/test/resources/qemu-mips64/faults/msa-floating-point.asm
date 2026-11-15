# Invalid in one lane and Inexact in another. Preserve the destination
# and the previous sticky flag when the enabled Invalid exception traps.
ldi.w $w2, 17
lui $8, 0x3f80
fill.w $w0, $8
insert.w $w0[0], $0
lui $8, 0x4040
fill.w $w1, $8
insert.w $w1[0], $0
ori $8, $0, 0x0808
ctcmsa $1, $8
fdiv.w $w2, $w0, $w1
