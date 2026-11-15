# Min times min is outside the signed fixed-point range. The second
# lane has a negative half-ULP product, so rounding changes its result.
ldi.w $w0, 1
slli.w $w0, $w0, 31
move.v $w1, $w0
ldi.w $w2, 1
slli.w $w2, $w2, 30
insve.w $w1[1], $w2[0]
daddiu $8, $0, -1
insert.w $w0[1], $8
ldi.w $w3, -1
srli.w $w3, $w3, 1
ldi.w $w4, 1
slli.w $w4, $w4, 31
move.v $w5, $w3
move.v $w6, $w4
mul_q.w $w2, $w0, $w1
st.b $w2, 0($5)
mulr_q.w $w7, $w0, $w1
st.b $w7, 16($5)
madd_q.w $w3, $w0, $w1
st.b $w3, 32($5)
maddr_q.w $w5, $w0, $w1
st.b $w5, 48($5)
msub_q.w $w4, $w0, $w1
st.b $w4, 64($5)
msubr_q.w $w6, $w0, $w1
st.b $w6, 80($5)
