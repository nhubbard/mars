# Round both signs toward positive infinity, then truncate.
ori $8, $0, 0x3ff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0xbff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $10, $0, 2
ctcmsa $1, $10
frint.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $10
ftint_s.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $10
ftint_u.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $10
ftrunc_s.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 48($5)
sw $9, 124($5)
ctcmsa $1, $10
ftrunc_u.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 64($5)
sw $9, 128($5)
