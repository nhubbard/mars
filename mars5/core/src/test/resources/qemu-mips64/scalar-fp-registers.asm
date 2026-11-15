# Check extension into a GPR and replacement of the high FPR half.
ld $8, 0($4)
ld $9, 8($4)
dmtc1 $8, $f9
dmfc1 $10, $f9
sd $10, 0($5)
mthc1 $9, $f9
mfhc1 $10, $f9
sd $10, 8($5)
mfc1 $10, $f9
sd $10, 16($5)
dmfc1 $10, $f9
sd $10, 24($5)

# Load and store the selected width at nonnaturally aligned locations.
lwc1 $f10, 1($4)
ldc1 $f11, 9($4)
swc1 $f10, 33($5)
sdc1 $f11, 41($5)
