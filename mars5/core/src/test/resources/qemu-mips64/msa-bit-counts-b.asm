# Cover the full-width sign bit and a lane of all zeroes.
ldi.b $w0, 1
slli.b $w1, $w0, 7
ldi.b $w2, -1
insert.b $w2[1], $0
sll.b $w3, $w0, $w2
pcnt.b $w4, $w2
nlzc.b $w5, $w2
nloc.b $w6, $w2
st.b $w1, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)

# Narrow to four signed/unsigned bits, then leave a whole-width lane intact.
ldi.b $w0, 127
daddiu $8, $0, -128
insert.b $w0[1], $8
sat_s.b $w2, $w0, 3
sat_u.b $w3, $w0, 3
sat_s.b $w4, $w0, 7
sat_u.b $w5, $w0, 7
st.b $w2, 80($5)
st.b $w3, 96($5)
st.b $w4, 112($5)
st.b $w5, 128($5)
