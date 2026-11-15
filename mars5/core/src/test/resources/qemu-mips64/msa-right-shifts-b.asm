# Shift positive and negative half-step values by one bit.
ldi.b $w0, -3
ori $8, $0, 3
insert.b $w0[1], $8
ldi.b $w1, 9
sra.b $w2, $w0, $w1
srl.b $w3, $w0, $w1
srar.b $w4, $w0, $w1
srlr.b $w5, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)

# Exercise maximum immediate counts and rounded shifts by zero.
srai.b $w2, $w0, 7
srli.b $w3, $w0, 7
srari.b $w4, $w0, 7
srlri.b $w5, $w0, 7
ldi.b $w7, 0
srar.b $w0, $w0, $w7
st.b $w2, 64($5)
st.b $w3, 80($5)
st.b $w4, 96($5)
st.b $w5, 112($5)
st.b $w0, 128($5)
