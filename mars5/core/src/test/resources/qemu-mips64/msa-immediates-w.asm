# The same input bits straddle the signed and unsigned ranges.
ldi.w $w0, -16
ori $8, $0, 15
insert.w $w0[1], $8
maxi_s.w $w2, $w0, -16
st.b $w2, 0($5)
maxi_u.w $w2, $w0, 31
st.b $w2, 16($5)
mini_s.w $w2, $w0, -16
st.b $w2, 32($5)
mini_u.w $w2, $w0, 31
st.b $w2, 48($5)
ceqi.w $w2, $w0, -16
st.b $w2, 64($5)
clti_s.w $w2, $w0, 15
st.b $w2, 80($5)
clti_u.w $w2, $w0, 31
st.b $w2, 96($5)
clei_s.w $w2, $w0, 15
st.b $w2, 112($5)
clei_u.w $w2, $w0, 31
st.b $w2, 128($5)
