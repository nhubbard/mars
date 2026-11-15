# The product rounds to one when computed separately. Fused cancellation
# exposes the low product bits in both signs.
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
sd $8, 0($4)
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
sd $8, 8($4)
ld.d $w0, 0($4)
ori $8, $0, 0x3fef
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xfffe
sd $8, 0($4)
ori $8, $0, 0x3fef
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xfffe
sd $8, 8($4)
ld.d $w1, 0($4)
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w3, 0($4)
ctcmsa $1, $0
move.v $w2, $w3
fmadd.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
move.v $w2, $w3
fmsub.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
