# Accumulate into nonzero lanes, including a destination/source alias.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ld.b $w2, 32($4)
move.v $w3, $w2
move.v $w4, $w2
dpadd_s.w $w2, $w0, $w1
dpadd_u.w $w3, $w0, $w1
dpsub_s.w $w4, $w0, $w1
dpsub_u.w $w1, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w1, 48($5)

# Adding the largest unsigned products into an all-ones wider lane wraps.
ldi.h $w0, -1
ldi.w $w2, -1
dpadd_u.w $w2, $w0, $w0
st.b $w2, 64($5)
