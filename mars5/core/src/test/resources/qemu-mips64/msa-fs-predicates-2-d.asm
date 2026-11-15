# Compare quiet NaN and ordered lanes of opposite signs.
ori $8, $0, 0x7ff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0011
sd $8, 0($4)
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w1, 0($4)
ctcmsa $1, $0
fsle.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fsule.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
fsor.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
fsune.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 48($5)
sw $9, 124($5)
ctcmsa $1, $0
fsne.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 64($5)
sw $9, 128($5)
