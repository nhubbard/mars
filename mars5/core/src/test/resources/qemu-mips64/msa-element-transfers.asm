# Replace the highest lane from a GPR, then extract it with both extensions.
ld.b $w0, 0($4)
ld.b $w1, 0($4)
ld.b $w2, 0($4)
ld.b $w3, 0($4)
daddiu $8, $0, -126
insert.b $w0[15], $8
insert.h $w1[7], $8
insert.w $w2[3], $8
insert.d $w3[1], $8
copy_s.b $9, $w0[15]
copy_u.b $10, $w0[15]
copy_s.h $11, $w1[7]
copy_u.h $12, $w1[7]
copy_s.w $13, $w2[3]
copy_u.w $14, $w2[3]
copy_s.d $15, $w3[1]

# Read lane zero from another vector or from the destination itself.
ld.b $w4, 16($4)
insve.b $w0[8], $w4[0]
insve.h $w1[4], $w4[0]
insve.w $w2[2], $w2[0]
insve.d $w3[0], $w4[0]
move.v $w5, $w4
ldi.b $w4, 0

st.b $w0, 0($5)
st.b $w1, 16($5)
st.b $w2, 32($5)
st.b $w3, 48($5)
st.b $w5, 64($5)
sd $9, 80($5)
sd $10, 88($5)
sd $11, 96($5)
sd $12, 104($5)
sd $13, 112($5)
sd $14, 120($5)
sd $15, 128($5)
