# Shift positive and negative half-step values by one bit.
ldi.d $w0, -3
ori $8, $0, 3
insert.d $w0[1], $8
ldi.d $w1, 65
sra.d $w2, $w0, $w1
srl.d $w3, $w0, $w1
srar.d $w4, $w0, $w1
srlr.d $w5, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)

# Exercise maximum immediate counts and rounded shifts by zero.
srai.d $w2, $w0, 63
srli.d $w3, $w0, 63
srari.d $w4, $w0, 63
srlri.d $w5, $w0, 63
ldi.d $w7, 0
srar.d $w0, $w0, $w7
st.b $w2, 64($5)
st.b $w3, 80($5)
st.b $w4, 96($5)
st.b $w5, 112($5)
st.b $w0, 128($5)
