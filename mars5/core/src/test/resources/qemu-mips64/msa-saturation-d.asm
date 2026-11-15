# Exercise numeric extrema and both mixed-sign saturating subtract forms.
ldi.d $w0, 1
slli.d $w0, $w0, 63
ldi.d $w2, -1
srli.d $w1, $w2, 1
ldi.d $w3, 1
ldi.d $w13, 0
adds_s.d $w4, $w0, $w2
adds_s.d $w5, $w1, $w3
adds_u.d $w6, $w2, $w3
adds_a.d $w7, $w0, $w3
subs_s.d $w8, $w0, $w3
subsus_u.d $w9, $w2, $w0
subsus_u.d $w10, $w0, $w0
subsuu_s.d $w11, $w2, $w13
subsuu_s.d $w12, $w13, $w2
st.d $w4, 0($5)
st.d $w5, 16($5)
st.d $w6, 32($5)
st.d $w7, 48($5)
st.d $w8, 64($5)
st.d $w9, 80($5)
st.d $w10, 96($5)
st.d $w11, 112($5)
st.d $w12, 128($5)
