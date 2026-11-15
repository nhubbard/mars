# The harness passes input in $4 and output in $5.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ld.b $w2, 32($4)
addv.b $w3, $w0, $w1
adds_s.b $w4, $w0, $w1
adds_u.b $w5, $w0, $w1
addv.d $w6, $w0, $w1
srli.d $w7, $w0, 9
vshf.b $w2, $w0, $w1
st.b $w3, 0($5)
st.b $w4, 16($5)
st.b $w5, 32($5)
st.b $w6, 48($5)
st.b $w7, 64($5)
st.b $w2, 80($5)
