# Opposite signs with equal magnitudes, including swapped operands.
ldi.b $w0, -5
ldi.b $w1, 5
max_a.b $w2, $w0, $w1
max_a.b $w3, $w1, $w0
min_a.b $w4, $w0, $w1
min_a.b $w5, $w1, $w0
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
# The most negative signed doubleword has the largest absolute value.
ldi.d $w0, 1
slli.d $w0, $w0, 63
ldi.d $w1, -1
srli.d $w1, $w1, 1
max_a.d $w2, $w0, $w1
min_a.d $w3, $w0, $w1
st.d $w2, 64($5)
st.d $w3, 80($5)
