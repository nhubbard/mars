# Exercise numeric extrema and both mixed-sign saturating subtract forms.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w2, -1
srli.w $w1, $w2, 1
ldi.w $w3, 1
ldi.w $w13, 0
adds_s.w $w4, $w0, $w2
adds_s.w $w5, $w1, $w3
adds_u.w $w6, $w2, $w3
adds_a.w $w7, $w0, $w3
subs_s.w $w8, $w0, $w3
subsus_u.w $w9, $w2, $w0
subsus_u.w $w10, $w0, $w0
subsuu_s.w $w11, $w2, $w13
subsuu_s.w $w12, $w13, $w2
st.w $w4, 0($5)
st.w $w5, 16($5)
st.w $w6, 32($5)
st.w $w7, 48($5)
st.w $w8, 64($5)
st.w $w9, 80($5)
st.w $w10, 96($5)
st.w $w11, 112($5)
st.w $w12, 128($5)
