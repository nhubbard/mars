# Compare truncated signed quotients and remainders, and unsigned division.
ldi.h $w0, -7
ldi.h $w1, 3
ldi.h $w2, -1
ldi.h $w3, 1
slli.h $w3, $w3, 15
div_s.h $w4, $w0, $w1
mod_s.h $w5, $w0, $w1
div_s.h $w6, $w3, $w1
mod_s.h $w7, $w3, $w1
div_u.h $w8, $w2, $w1
mod_u.h $w9, $w2, $w1
st.h $w4, 0($5)
st.h $w5, 16($5)
st.h $w6, 32($5)
st.h $w7, 48($5)
st.h $w8, 64($5)
st.h $w9, 80($5)
