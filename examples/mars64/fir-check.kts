import edu.missouristate.mars.api.MarsTarget

case("signed FIR windows", target = MarsTarget.MARS64) {
    requireDataLabel("output")
    startAt("main")
    runUntil("scalar_sample")
    expectRegister("s4", 31)
    runToExit()
    expectOutput("MSA FIR matches the scalar reference\n")
    listOf(31, -19, 0, 1, 27, -57, 75, -75).forEachIndexed { index, expected ->
        expectWord("output", expected, offset = index * 4)
    }
}
