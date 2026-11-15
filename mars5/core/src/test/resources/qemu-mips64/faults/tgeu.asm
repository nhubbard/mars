# Opposite signs distinguish signed from unsigned comparisons.
daddiu $8, $0, -1
ori $9, $0, 1
daddiu $12, $0, 16
tgeu $9, $8
daddiu $12, $12, 1
tgeu $8, $9
nop
