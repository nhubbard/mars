.set noreorder
.text
.globl probe
.type probe, @function
probe:
	llwp $11,$9,($12)
	scwp $11,$9,($12)
	lldp $11,$9,($12)
	scdp $11,$9,($12)
	ll $11,-256($12)
	sc $11,252($12)
	lld $11,-248($12)
	scd $11,248($12)
	teq $12,$9,7
	tge $12,$9,255
	tgeu $12,$9,256
	tlt $12,$9,511
	tltu $12,$9,512
	tne $15,$10,1023
.size probe, .-probe
