# Slide each row by its last column, using both selector forms.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ld.b $w2, 16($4)
daddiu $8, $0, -1
sld.b $w1, $w0[$8]
sldi.b $w2, $w0[15]
st.b $w1, 0($5)
st.b $w2, 16($5)

# Exercise a slide whose two source rectangles are the same.
ld.b $w3, 0($4)
sldi.b $w3, $w3[1]
st.b $w3, 32($5)

# Broadcast a lane with a wrapped GPR index and an immediate index.
daddiu $8, $0, 17
splat.b $w4, $w0[$8]
ld.b $w5, 0($4)
splati.b $w5, $w5[1]
st.b $w4, 48($5)
st.b $w5, 64($5)

# A slide of zero columns copies the source rectangle.
sldi.b $w1, $w0[0]
st.b $w1, 80($5)

# Reverse each group of four lanes and write over the source.
shf.b $w0, $w0, 0x1b
st.b $w0, 96($5)
