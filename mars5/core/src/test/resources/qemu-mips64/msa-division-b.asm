# Compare truncated signed quotients and remainders, and unsigned division.
ldi.b $w0, -7
ldi.b $w1, 3
ldi.b $w2, -1
ldi.b $w3, 1
slli.b $w3, $w3, 7
div_s.b $w4, $w0, $w1
mod_s.b $w5, $w0, $w1
div_s.b $w6, $w3, $w1
mod_s.b $w7, $w3, $w1
div_u.b $w8, $w2, $w1
mod_u.b $w9, $w2, $w1
st.b $w4, 0($5)
st.b $w5, 16($5)
st.b $w6, 32($5)
st.b $w7, 48($5)
st.b $w8, 64($5)
st.b $w9, 80($5)
