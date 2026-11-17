case("positive input", input = "20\n") {
    runUntil("calculate")
    expectRegister("v0", 20)
    step()
    expectRegister("t0", 40)
    step()
    expectRegister("t0", 41)
    runToExit()
    expectOutput("Enter a small integer: Twice your number plus one is: 41\n")
}

case("zero input", input = "0\n") {
    runUntil("report")
    expectRegister("t0", 1)
    runToExit()
    expectOutput("Enter a small integer: Twice your number plus one is: 1\n")
}

case("negative input", input = "-3\n") {
    runUntil("report")
    expectRegister("t0", -5)
    runToExit()
    expectOutput("Enter a small integer: Twice your number plus one is: -5\n")
}
