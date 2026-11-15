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
.size probe, .-probe
