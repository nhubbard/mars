#!/usr/bin/env python3
"""Compile a small upstream GCC C torture corpus to MIPS64r6 assembly."""

import argparse
from hashlib import sha256
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

FIXTURES = Path(__file__).resolve().parents[1] / "src/test/resources/gcc-torture"
CASES = (
    "20010518-1",
    "20011109-1",
    "20030903-1",
    "20140622-1",
    "20141125-1",
    "pr98727",
)
OPTIONS = [
    "-march=mips64r6",
    "-mabi=64",
    "-EB",
    "-mno-abicalls",
    "-mexplicit-relocs",
    "-fno-pic",
    "-G0",
    "-ffreestanding",
    "-fno-asynchronous-unwind-tables",
    "-fno-ident",
    "-Dmain=mars_gcc_main",
    "-Dabort=mars_gcc_abort",
    "-Dexit=mars_gcc_exit",
    "-D__builtin_abort=mars_gcc_abort",
    "-include",
    "mars-gcc-runtime.h",
]


def capture(compiler, corpus, driver_dir):
    driver = [compiler]
    if driver_dir is not None:
        driver.append("-B" + str(driver_dir.resolve()) + "/")
    version = subprocess.check_output([*driver, "-dumpfullversion"], text=True).strip()
    if version != "15.2.0":
        raise ValueError(f"Expected GCC 15.2.0, received {version}")
    provenance = [f"# GCC {version} gcc.c-torture/execute", "# Upstream file\tSHA-256"]
    with TemporaryDirectory(prefix="mars-gcc-") as tmp:
        work = Path(tmp)
        (work / "mars-gcc-runtime.h").write_text(
            "void mars_gcc_abort(void) __attribute__((noreturn));\n"
            "void mars_gcc_exit(int) __attribute__((noreturn));\n"
        )
        for case in CASES:
            filename = f"{case}.c"
            source = (corpus / filename).read_bytes()
            provenance.append(f"{filename}\t{sha256(source).hexdigest()}")
            (work / filename).write_bytes(source)
            for optimization in ("O0", "O2"):
                output = f"{case}-{optimization}.s"
                subprocess.run(
                    [
                        *driver,
                        *OPTIONS,
                        f"-{optimization}",
                        "-S",
                        filename,
                        "-o",
                        output,
                    ],
                    cwd=work,
                    check=True,
                )
                yield output, (work / output).read_text()
    yield "sources.tsv", "\n".join(provenance) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "corpus",
        type=Path,
        help="Upstream gcc/testsuite/gcc.c-torture/execute directory",
    )
    parser.add_argument("--gcc", default="mips64-unknown-elf-gcc")
    parser.add_argument(
        "--driver-dir", type=Path, help="GCC build directory containing xgcc and cc1"
    )
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    outputs = dict(capture(args.gcc, args.corpus, args.driver_dir))
    if args.write:
        FIXTURES.mkdir(parents=True, exist_ok=True)
        for filename, content in outputs.items():
            (FIXTURES / filename).write_text(content)
    else:
        for filename, content in outputs.items():
            if (FIXTURES / filename).read_text() != content:
                raise ValueError(
                    f"GCC output changed for {filename}. Regenerate with --write after review."
                )
        print(
            f"Verified {len(outputs) - 1} GCC assembly fixtures and their upstream hashes."
        )


if __name__ == "__main__":
    main()
