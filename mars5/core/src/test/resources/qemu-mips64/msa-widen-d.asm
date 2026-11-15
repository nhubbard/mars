# Distinct bytes make the chosen odd and even source lanes observable.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
hadd_s.d $w2, $w0, $w1
st.b $w2, 0($5)
hadd_u.d $w2, $w0, $w1
st.b $w2, 16($5)
hsub_s.d $w2, $w0, $w1
st.b $w2, 32($5)
hsub_u.d $w2, $w0, $w1
st.b $w2, 48($5)
dotp_s.d $w2, $w0, $w1
st.b $w2, 64($5)
dotp_u.d $w2, $w0, $w1
st.b $w2, 80($5)
# Maximal narrow elements exercise sign extension and product overflow.
ldi.w $w0, -1
dotp_s.d $w2, $w0, $w0
st.b $w2, 96($5)
dotp_u.d $w2, $w0, $w0
st.b $w2, 112($5)
