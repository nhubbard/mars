# Load binary16 values from GPRs into the lowest and highest lanes.
ldi.h $w0, 1
ori $8, $0, 0x8000
insert.h $w0[1], $8
ori $8, $0, 0x7c01
insert.h $w0[4], $8
ori $8, $0, 0xbe00
insert.h $w0[7], $8
ctcmsa $1, $0
fexupl.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fexupr.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
ffql.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
ffqr.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 124($5)
