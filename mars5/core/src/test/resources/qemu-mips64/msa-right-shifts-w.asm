# Shift positive and negative half-step values by one bit.
ldi.w $w0, -3
ori $8, $0, 3
insert.w $w0[1], $8
ldi.w $w1, 33
sra.w $w2, $w0, $w1
srl.w $w3, $w0, $w1
srar.w $w4, $w0, $w1
srlr.w $w5, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)

# Exercise maximum immediate counts and rounded shifts by zero.
srai.w $w2, $w0, 31
srli.w $w3, $w0, 31
srari.w $w4, $w0, 31
srlri.w $w5, $w0, 31
ldi.w $w7, 0
srar.w $w0, $w0, $w7
st.b $w2, 64($5)
st.b $w3, 80($5)
st.b $w4, 96($5)
st.b $w5, 112($5)
st.b $w0, 128($5)
