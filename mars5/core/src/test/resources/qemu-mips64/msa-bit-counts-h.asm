# Cover the full-width sign bit and a lane of all zeroes.
ldi.h $w0, 1
slli.h $w1, $w0, 15
ldi.h $w2, -1
insert.h $w2[1], $0
sll.h $w3, $w0, $w2
pcnt.h $w4, $w2
nlzc.h $w5, $w2
nloc.h $w6, $w2
st.b $w1, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)

# Narrow to four signed/unsigned bits, then leave a whole-width lane intact.
ldi.h $w0, 127
daddiu $8, $0, -128
insert.h $w0[1], $8
sat_s.h $w2, $w0, 3
sat_u.h $w3, $w0, 3
sat_s.h $w4, $w0, 15
sat_u.h $w5, $w0, 15
st.b $w2, 80($5)
st.b $w3, 96($5)
st.b $w4, 112($5)
st.b $w5, 128($5)
