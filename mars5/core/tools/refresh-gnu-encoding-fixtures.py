#!/usr/bin/env python3
"""Refresh MARS64 encoding and operand fixtures with GNU GAS and objdump."""

import argparse
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory

FIXTURES = Path(__file__).resolve().parents[1] / "src/test/resources/gnu-mips64"
SOURCE = FIXTURES / "linked-memory.s"
DISASSEMBLY = re.compile(r"^\s*[\da-f]+:\s*([\da-f]{8})\s+(\w+)\s+([\d$,()\-]+)\s*$", re.MULTILINE)


def capture(assembler, objdump):
    version = subprocess.check_output([assembler, "--version"], text=True).splitlines()[0]
    dump_version = subprocess.check_output([objdump, "--version"], text=True).splitlines()[0]
    instructions = [line.strip() for line in SOURCE.read_text().splitlines() if line.startswith("\t")]
    results = [f"# {version}", f"# {dump_version}", "# source\tbyte order\tword\tmnemonic\toperands"]
    with TemporaryDirectory(prefix="mars-gnu-") as work:
        image = Path(work) / "linked-memory.o"
        for byte_order, option in (("big", "-EB"), ("little", "-EL")):
            subprocess.run([assembler, "-mips64r6", "-mabi=64", option, "-o", image, SOURCE], check=True)
            dump = subprocess.check_output(
                [objdump, "-d", "-M", "no-aliases,reg-names=numeric", "--disassemble=probe", image], text=True,
            )
            if not re.search(rf"file format elf64-(?:trad)?{byte_order}mips\b", dump):
                raise ValueError(f"Unexpected object format for {byte_order} endian:\n{dump}")
            records = DISASSEMBLY.findall(dump)
            for source, (word, mnemonic, operands) in zip(instructions, records, strict=True):
                decoded = ",".join(re.findall(r"-?\d+", operands))
                results.append("\t".join((source, byte_order, word, mnemonic, decoded)))
    return "\n".join(results) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as", dest="assembler", default="mips64-unknown-elf-as")
    parser.add_argument("--objdump", default="mips64-unknown-elf-objdump")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    results = capture(args.assembler, args.objdump)
    if args.write:
        (FIXTURES / "linked-memory.tsv").write_text(results)
    else:
        print(results, end="")


if __name__ == "__main__":
    main()
