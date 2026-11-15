# The harness passes input in $4 and output in $5.
# Mixed element widths reveal the byte-to-lane mapping in each byte order.
daddiu $8, $4, 1
daddiu $9, $5, 1
ld.b $w0, 0($8)
ld.h $w1, 2($8)
ld.w $w2, 4($8)
ld.d $w3, 8($8)
st.b $w0, 0($9)
st.b $w1, 16($9)
st.b $w2, 32($9)
st.b $w3, 48($9)
st.h $w0, 64($9)
st.w $w0, 80($9)
st.d $w0, 96($9)
st.d $w3, 112($9)
