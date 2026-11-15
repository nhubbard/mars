# Min times min is outside the signed fixed-point range. The second
# lane has a negative half-ULP product, so rounding changes its result.
ldi.h $w0, 1
slli.h $w0, $w0, 15
move.v $w1, $w0
ldi.h $w2, 1
slli.h $w2, $w2, 14
insve.h $w1[1], $w2[0]
daddiu $8, $0, -1
insert.h $w0[1], $8
ldi.h $w3, -1
srli.h $w3, $w3, 1
ldi.h $w4, 1
slli.h $w4, $w4, 15
move.v $w5, $w3
move.v $w6, $w4
mul_q.h $w2, $w0, $w1
st.b $w2, 0($5)
mulr_q.h $w7, $w0, $w1
st.b $w7, 16($5)
madd_q.h $w3, $w0, $w1
st.b $w3, 32($5)
maddr_q.h $w5, $w0, $w1
st.b $w5, 48($5)
msub_q.h $w4, $w0, $w1
st.b $w4, 64($5)
msubr_q.h $w6, $w0, $w1
st.b $w6, 80($5)
