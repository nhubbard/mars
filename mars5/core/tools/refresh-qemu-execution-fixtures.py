#!/usr/bin/env python3
"""Show or refresh the MIPS64 execution fixtures using LLVM and QEMU I6400."""

import argparse
import os
from pathlib import Path
import re
import select
import subprocess
import tempfile
import time

FIXTURES = Path(__file__).resolve().parents[1] / "src/test/resources/qemu-mips64"
TEXT_BASE = "0xffffffff80010000"
INPUT = "ffffffff80020000"
OUTPUT = "ffffffff80021000"
OUTPUT_SIZE = 144

# Enable the FPU, 64-bit FPRs, kernel extended addressing (KX), and MSA.
STARTUP = """\
.set noreorder
.text
.global _start
_start:
    lui $9, 0x2400
    ori $9, $9, 0x0080
    mtc0 $9, $12, 0
    mfc0 $8, $16, 5
    lui $9, 0x0800
    or $8, $8, $9
    mtc0 $8, $16, 5
    ehb
    lui $4, 0x8002
    daddiu $5, $4, 0x1000
"""

STOP = """\
.global done
done:
    b done
    nop
"""

LINKER_SCRIPT = f"""\
ENTRY(_start)
PHDRS {{ ram PT_LOAD FLAGS(5); exceptions PT_LOAD FLAGS(5); }}
SECTIONS {{
    .exception 0xffffffff80000180 : {{ *(.exception) }} :exceptions
    . = {TEXT_BASE};
    .text : {{ *(.text) }} :ram
    /DISCARD/ : {{ *(.MIPS.abiflags) *(.MIPS.options) *(.reginfo) *(.got) }}
}}
"""


def exchange(process, command):
    payload = command.encode("ascii")
    checksum = sum(payload) % 256
    process.stdin.write(b"$" + payload + f"#{checksum:02x}".encode("ascii"))
    process.stdin.flush()
    deadline = time.monotonic() + 5
    fd = process.stdout.fileno()

    def read_byte():
        if not select.select([fd], [], [], max(0, deadline - time.monotonic()))[0]:
            raise TimeoutError(f"QEMU timed out during {command.split(',')[0]}")
        return os.read(fd, 1)

    while (byte := read_byte()) not in (b"$", b""):
        if byte == b"-":
            raise RuntimeError("QEMU rejected a debugger packet")
    if not byte:
        raise RuntimeError("QEMU exited before responding")
    reply = bytearray()
    while (byte := read_byte()) not in (b"#", b""):
        reply.extend(byte)
    checksum = read_byte() + read_byte() if byte else b""
    if checksum != f"{sum(reply) % 256:02x}".encode("ascii"):
        raise RuntimeError("Invalid QEMU debugger response checksum")
    process.stdin.write(b"+")
    process.stdin.flush()
    return reply.decode("ascii")


def execute(elf, stop_address, triple, inputs):
    command = [
        f"qemu-system-{triple}", "-machine", "malta", "-cpu", "I6400",
        "-accel", "tcg", "-display", "none", "-monitor", "none", "-serial", "none",
        "-nic", "none", "-no-reboot", "-kernel", str(elf), "-S", "-gdb", "stdio",
    ]
    with tempfile.TemporaryFile() as errors, subprocess.Popen(
        command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors,
    ) as process:
        try:
            for address, data in [(INPUT, inputs), (OUTPUT, bytes([0xA5]) * OUTPUT_SIZE)]:
                reply = exchange(process, f"M{address},{len(data):x}:{data.hex()}")
                if reply != "OK":
                    raise RuntimeError(f"QEMU could not initialize 0x{address}: {reply}")
            reply = exchange(process, f"Z0,{stop_address},4")
            if reply != "OK":
                raise RuntimeError(f"QEMU could not install the stop breakpoint: {reply}")
            stopped = exchange(process, "c")
            if not stopped.startswith(("T05", "S05")):
                raise RuntimeError(f"Unexpected QEMU stop: {stopped}")
            # Register 37 in QEMU's MIPS GDB register packet is the program counter.
            pc_bytes = bytes.fromhex(exchange(process, "p25"))
            byte_order = "little" if triple.endswith("el") else "big"
            if int.from_bytes(pc_bytes, byte_order) != int(stop_address, 16):
                raise RuntimeError(f"QEMU stopped at a different instruction: {pc_bytes.hex()}")
            output = bytes.fromhex(exchange(process, f"m{OUTPUT},{OUTPUT_SIZE:x}"))
            if len(output) != OUTPUT_SIZE:
                raise RuntimeError(f"QEMU returned {len(output)} output bytes")
            return output.hex()
        except (BrokenPipeError, RuntimeError, TimeoutError, ValueError) as failure:
            errors.seek(0)
            detail = errors.read().decode("utf-8", errors="replace").strip()
            raise RuntimeError(f"{triple} execution failed: {failure}\n{detail}") from failure
        finally:
            if process.poll() is None:
                process.kill()


def capture():
    inputs = bytes.fromhex((FIXTURES / "input.hex").read_text())
    qemu = subprocess.check_output(["qemu-system-mips64", "--version"], text=True).splitlines()[0].strip()
    llvm = next(line.strip() for line in subprocess.check_output(["llvm-mc", "--version"], text=True).splitlines() if "LLVM version" in line)
    linker = subprocess.check_output(["ld.lld", "--version"], text=True).splitlines()[0].strip()
    lines = [f"# {qemu}", f"# {llvm}; {linker}", "# program,byte_order,output_hex"]
    with tempfile.TemporaryDirectory(prefix="mars-qemu-") as directory:
        work = Path(directory)
        source, obj, elf, layout = (work / name for name in ("probe.s", "probe.o", "probe.elf", "ram.ld"))
        layout.write_text(LINKER_SCRIPT)
        probes = sorted(FIXTURES.glob("*.asm")) + sorted(FIXTURES.glob("faults/*.asm"))
        for probe in probes:
            # Some Release 6 forms have specified encodings but no LLVM mnemonic.
            program = re.sub(r"(?m)^.*# llvm-word (0x[\da-f]{8})$", r".word \1", probe.read_text())
            name = probe.stem
            if probe.parent.name == "faults":
                name = "faults/" + name
                program = (
                    "dla $6, probe_start\nprobe_start:\n" + program
                    + "\nmissed_fault:\nb missed_fault\nnop\n"
                    + '.section .exception,"ax",@progbits\n'
                    + (FIXTURES / "exception-handler.s").read_text()
                )
            source.write_text(STARTUP + program + "\n" + STOP)
            for triple, byte_order in [("mips64", "big"), ("mips64el", "little")]:
                subprocess.run(
                    ["llvm-mc", f"-triple={triple}", "-mcpu=mips64r6", "-mattr=+msa,+crc",
                     "-filetype=obj", str(source), "-o", str(obj)], check=True, timeout=10,
                )
                subprocess.run(["ld.lld", "-T", str(layout), str(obj), "-o", str(elf)], check=True, timeout=10)
                symbols = subprocess.check_output(["llvm-nm", str(elf)], text=True, timeout=10)
                stop_address = next(line.split()[0] for line in symbols.splitlines() if line.endswith(" T done"))
                lines.append(f"{name},{byte_order},{execute(elf, stop_address, triple, inputs)}")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Replace the checked-in results after all probes succeed")
    args = parser.parse_args()
    results = capture()
    if args.write:
        (FIXTURES / "expected.csv").write_text(results)
    else:
        print(results, end="")


if __name__ == "__main__":
    main()
