# Large integer inputs require rounding, with different signed interpretations.
ori $8, $0, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
sd $8, 0($4)
ori $8, $0, 0x0020
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
sd $8, 8($4)
ld.d $w0, 0($4)
ori $10, $0, 2
ctcmsa $1, $10
ffint_s.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $10
ffint_u.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
