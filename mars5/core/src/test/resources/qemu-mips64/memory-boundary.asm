# The output pointer is 4 KiB aligned. Cross that boundary with an ordinary
# scalar transfer and a vector transfer, then copy the complete results back.
daddiu $8, $5, -16
ld $9, 0($4)
sd $9, 13($8)
ld $10, 13($8)
sd $10, 16($5)
ld.b $w0, 16($4)
st.d $w0, 8($8)
ld.b $w1, 8($8)
st.b $w1, 32($5)
