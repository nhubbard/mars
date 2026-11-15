# Cover the full-width sign bit and a lane of all zeroes.
ldi.d $w0, 1
slli.d $w1, $w0, 63
ldi.d $w2, -1
insert.d $w2[1], $0
sll.d $w3, $w0, $w2
pcnt.d $w4, $w2
nlzc.d $w5, $w2
nloc.d $w6, $w2
st.b $w1, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)

# Narrow to four signed/unsigned bits, then leave a whole-width lane intact.
ldi.d $w0, 127
daddiu $8, $0, -128
insert.d $w0[1], $8
sat_s.d $w2, $w0, 3
sat_u.d $w3, $w0, 3
sat_s.d $w4, $w0, 63
sat_u.d $w5, $w0, 63
st.b $w2, 80($5)
st.b $w3, 96($5)
st.b $w4, 112($5)
st.b $w5, 128($5)
