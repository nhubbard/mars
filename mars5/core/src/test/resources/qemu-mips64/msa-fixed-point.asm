# Q15 and Q31 products and accumulators, including the most negative operand.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w1, -1
move.v $w2, $w1
move.v $w3, $w1
mul_q.w $w4, $w0, $w0
maddr_q.w $w2, $w0, $w0
msubr_q.w $w3, $w0, $w0
st.w $w4, 0($5)
st.w $w2, 16($5)
st.w $w3, 32($5)
ld.h $w0, 0($4)
ld.h $w1, 16($4)
mulr_q.h $w2, $w0, $w1
dotp_s.w $w3, $w0, $w1
dpadd_u.w $w4, $w0, $w1
hsub_u.w $w5, $w0, $w1
st.h $w2, 48($5)
st.w $w3, 64($5)
st.w $w4, 80($5)
st.w $w5, 96($5)
