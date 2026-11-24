#!/usr/bin/env python3
"""Sample deterministic Berkeley TestFloat 3e vectors for MARS64."""

import argparse
from hashlib import sha256
from itertools import islice
from pathlib import Path
import subprocess

FIXTURE = (
    Path(__file__).resolve().parents[1] / "src/test/resources/testfloat/expected.tsv"
)
ROUNDING_MODES = ("rnear_even", "rminMag", "rmax", "rmin")
FUNCTIONS = (
    [
        f"{precision}_{operation}"
        for precision in ("f32", "f64")
        for operation in (
            "add",
            "sub",
            "mul",
            "div",
            "mulAdd",
            "mulSub",
            "sqrt",
            "roundToInt",
            "to_i32",
            "to_i64",
            "to_ui32",
            "to_ui64",
        )
        if not operation.startswith("to_ui") or precision[1:] == operation[5:]
    ]
    + [
        f"{source}_to_{destination}"
        for destination in ("f32", "f64")
        for source in ("i32", "i64", "ui32", "ui64")
        if not source.startswith("ui") or destination[1:] == source[2:]
    ]
    + ["f64_to_f32", "f32_to_f64", "f32_to_f16", "f16_to_f32"]
)
PREFIX_SIZE = 4096
SAMPLES_PER_FLAG = 8


def reference_operands(operation, arguments):
    if operation.endswith("_mulSub"):
        # MIPS computes accumulator minus product. Flip the first FMA input's sign.
        width = len(arguments[0])
        sign = 1 << (width * 4 - 1)
        arguments[0] = f"{int(arguments[0], 16) ^ sign:0{width}x}"
    return arguments


def sample(generator, operation, rounding):
    # TestFloat's full fused-multiply-add stream contains millions of cases.
    # Keep coverage of different exception combinations in a bounded sample.
    generator_function = operation.replace("_mulSub", "_mulAdd")
    command = [
        generator,
        "-seed",
        "42",
        "-forever",
        "-tininessafter",
        "-exact",
        f"-{rounding}",
        generator_function,
    ]
    buckets = {}
    with subprocess.Popen(command, stdout=subprocess.PIPE, text=True) as process:
        for line in islice(process.stdout, PREFIX_SIZE):
            *arguments, result, flags = line.strip().lower().split()
            operands = ",".join(reference_operands(operation, arguments))
            buckets.setdefault(flags, []).append((operands, result))
        if process.poll() is None:
            process.kill()
        process.wait()
    if sum(map(len, buckets.values())) != PREFIX_SIZE:
        raise ValueError(
            f"TestFloat did not generate {PREFIX_SIZE} vectors for {operation} {rounding}"
        )
    for flags, cases in sorted(buckets.items()):
        ranked = sorted(
            set(cases), key=lambda case: sha256("\t".join(case).encode()).digest()
        )
        for arguments, result in ranked[:SAMPLES_PER_FLAG]:
            yield "\t".join((operation, rounding, arguments, result, flags))


def capture(generator):
    yield "# Berkeley TestFloat 3e with Berkeley SoftFloat 3e"
    yield "# Function\tRounding\tOperands\tResult\tException flags"
    for operation in FUNCTIONS:
        for rounding in ROUNDING_MODES:
            yield from sample(generator, operation, rounding)


def verify(verifier):
    batches = {}
    for line in FIXTURE.read_text().splitlines():
        if line.startswith("#"):
            continue
        operation, rounding, operands, result, flags = line.split("\t")
        arguments = reference_operands(operation, operands.split(","))
        batches.setdefault((operation, rounding), []).append(
            " ".join((*arguments, result, flags))
        )
    expected = {
        (function, rounding) for function in FUNCTIONS for rounding in ROUNDING_MODES
    }
    if batches.keys() != expected:
        raise ValueError(
            "The fixture must cover every expected function and rounding mode"
        )
    for (operation, rounding), vectors in batches.items():
        function = operation.replace("_mulSub", "_mulAdd")
        check = subprocess.run(
            [
                verifier,
                "-checkAll",
                "-tininessafter",
                "-exact",
                f"-{rounding}",
                function,
            ],
            input="\n".join(vectors) + "\n",
            text=True,
            capture_output=True,
        )
        if check.returncode != 0:
            raise ValueError(
                f"TestFloat could not verify {operation} {rounding}:\n{check.stdout}{check.stderr}"
            )
    print(
        f"Verified {sum(map(len, batches.values()))} vectors in {len(batches)} batches"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generator", default="testfloat_gen")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--verify", metavar="TESTFLOAT_VER")
    args = parser.parse_args()
    if args.verify:
        verify(args.verify)
        return
    result = "\n".join(capture(args.generator)) + "\n"
    if args.write:
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(result)
    else:
        print(result, end="")


if __name__ == "__main__":
    main()
