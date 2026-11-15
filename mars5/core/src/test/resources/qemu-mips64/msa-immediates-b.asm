# The same input bits straddle the signed and unsigned ranges.
ldi.b $w0, -16
ori $8, $0, 15
insert.b $w0[1], $8
maxi_s.b $w2, $w0, -16
st.b $w2, 0($5)
maxi_u.b $w2, $w0, 31
st.b $w2, 16($5)
mini_s.b $w2, $w0, -16
st.b $w2, 32($5)
mini_u.b $w2, $w0, 31
st.b $w2, 48($5)
ceqi.b $w2, $w0, -16
st.b $w2, 64($5)
clti_s.b $w2, $w0, 15
st.b $w2, 80($5)
clti_u.b $w2, $w0, 31
st.b $w2, 96($5)
clei_s.b $w2, $w0, 15
st.b $w2, 112($5)
clei_u.b $w2, $w0, 31
st.b $w2, 128($5)
