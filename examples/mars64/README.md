# MARS64 FIR filter

The program filters eight overlapping windows of signed 16-bit samples.
Each window is multiplied by eight signed coefficients. The MSA loop
uses `dotp_s.w` to sum adjacent products into four word lanes. A scalar
subroutine recomputes each output using 64-bit integer arithmetic.
The program prints a success message only if all eight results agree.

The data starts at `0x100000000` and the code starts at `0x100400000`.
The eight words at `output` should be `31, -19, 0, 1, 27, -57, 75, -75`.

From the repository root, build the CLI with JDK 25 and run the example.

```sh
./mars5/gradlew -p mars5 :cli:jar
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar nc --target mars64 --start-at-main examples/mars64/fir.mars
java -jar mars5/cli/build/libs/cli-1.0-SNAPSHOT.jar grade examples/mars64/fir-check.kts examples/mars64/fir.mars
```

To save the success message as well as print it, append `-- fir.ok` to the run command.

In IntelliJ, open `fir.mars`, select **MARS64** and the **Default** memory
layout in the MARS project settings, and enable **Start at main** in the run
configuration. Debug with a breakpoint on `balc scalar_sample`. At the
first stop, `$s4` holds 31 and `$w2` holds the four partial sums. Step over
the call to compare `$v0` with `$s4`, or step into it to follow the scalar
loop. Inspect the resulting words at `output` in the Memory view.
