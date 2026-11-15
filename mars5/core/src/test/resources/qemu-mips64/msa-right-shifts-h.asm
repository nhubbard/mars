# Shift positive and negative half-step values by one bit.
ldi.h $w0, -3
ori $8, $0, 3
insert.h $w0[1], $8
ldi.h $w1, 17
sra.h $w2, $w0, $w1
srl.h $w3, $w0, $w1
srar.h $w4, $w0, $w1
srlr.h $w5, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)

# Exercise maximum immediate counts and rounded shifts by zero.
srai.h $w2, $w0, 15
srli.h $w3, $w0, 15
srari.h $w4, $w0, 15
srlri.h $w5, $w0, 15
ldi.h $w7, 0
srar.h $w0, $w0, $w7
st.b $w2, 64($5)
st.b $w3, 80($5)
st.b $w4, 96($5)
st.b $w5, 112($5)
st.b $w0, 128($5)
