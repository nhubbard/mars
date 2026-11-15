# Exact tiny results with gradual underflow and with flush-to-zero.
lui $8, 0x0080
fill.w $w0, $8
lui $8, 0x8080
insert.w $w0[1], $8
lui $8, 0x3f00
fill.w $w1, $8
ctcmsa $1, $0
fmul.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 96($5)
lui $10, 0x0100
ctcmsa $1, $10
fmul.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 100($5)

# A flushed input still raises inexact even when the new result is exact.
ori $8, $0, 1
fill.w $w3, $8
lui $8, 0x8000
ori $8, $8, 1
insert.w $w3[1], $8
lui $8, 0x3f80
fill.w $w4, $8
ctcmsa $1, $10
fadd.w $w2, $w3, $w4
cfcmsa $9, $1
st.w $w2, 32($5)
sw $9, 104($5)

# The same flushed input compares equal to zero without raising inexact.
ldi.w $w5, 0
ctcmsa $1, $10
fceq.w $w2, $w3, $w5
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 108($5)

# Directed overflow of positive and negative finite values toward zero.
lui $8, 0x7f7f
ori $8, $8, 0xffff
fill.w $w6, $8
lui $8, 0xff7f
ori $8, $8, 0xffff
insert.w $w6[1], $8
lui $8, 0x4000
fill.w $w7, $8
ori $8, $0, 1
ctcmsa $1, $8
fmul.w $w2, $w6, $w7
cfcmsa $9, $1
st.w $w2, 64($5)
sw $9, 112($5)

# Tiny, inexact quotient with gradual underflow.
lui $8, 0x4040
fill.w $w7, $8
ctcmsa $1, $0
fdiv.w $w2, $w0, $w7
cfcmsa $9, $1
st.w $w2, 80($5)
sw $9, 116($5)
