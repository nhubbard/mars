# Ordinary scaling, finite overflow and signed exact or inexact underflow.
lui $8, 0x3fc0
sw $8, 0($4)
lui $8, 0xbf80
sw $8, 4($4)
lui $8, 0x3f80
sw $8, 8($4)
lui $8, 0xbfc0
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x0000
ori $8, $8, 0x0003
sw $8, 0($4)
lui $8, 0xffff
ori $8, $8, 0xff6b
sw $8, 4($4)
lui $8, 0x0000
ori $8, $8, 0x0080
sw $8, 8($4)
lui $8, 0xffff
ori $8, $8, 0xff6a
sw $8, 12($4)
ld.w $w1, 0($4)
ctcmsa $1, $0
fexp2.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
