# Exercise numeric extrema and both mixed-sign saturating subtract forms.
ldi.h $w0, 1
slli.h $w0, $w0, 15
ldi.h $w2, -1
srli.h $w1, $w2, 1
ldi.h $w3, 1
ldi.h $w13, 0
adds_s.h $w4, $w0, $w2
adds_s.h $w5, $w1, $w3
adds_u.h $w6, $w2, $w3
adds_a.h $w7, $w0, $w3
subs_s.h $w8, $w0, $w3
subsus_u.h $w9, $w2, $w0
subsus_u.h $w10, $w0, $w0
subsuu_s.h $w11, $w2, $w13
subsuu_s.h $w12, $w13, $w2
st.h $w4, 0($5)
st.h $w5, 16($5)
st.h $w6, 32($5)
st.h $w7, 48($5)
st.h $w8, 64($5)
st.h $w9, 80($5)
st.h $w10, 96($5)
st.h $w11, 112($5)
st.h $w12, 128($5)
