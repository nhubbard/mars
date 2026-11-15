# Exercise numeric extrema and both mixed-sign saturating subtract forms.
ldi.b $w0, 1
slli.b $w0, $w0, 7
ldi.b $w2, -1
srli.b $w1, $w2, 1
ldi.b $w3, 1
ldi.b $w13, 0
adds_s.b $w4, $w0, $w2
adds_s.b $w5, $w1, $w3
adds_u.b $w6, $w2, $w3
adds_a.b $w7, $w0, $w3
subs_s.b $w8, $w0, $w3
subsus_u.b $w9, $w2, $w0
subsus_u.b $w10, $w0, $w0
subsuu_s.b $w11, $w2, $w13
subsuu_s.b $w12, $w13, $w2
st.b $w4, 0($5)
st.b $w5, 16($5)
st.b $w6, 32($5)
st.b $w7, 48($5)
st.b $w8, 64($5)
st.b $w9, 80($5)
st.b $w10, 96($5)
st.b $w11, 112($5)
st.b $w12, 128($5)
