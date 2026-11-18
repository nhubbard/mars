# File fixtures in assignment checks

`provideFiles` copies input bytes into a case-local in-memory file environment. Exact
path names resolve within that environment. No supplied path is opened on the host.
Without a `writablePaths` set, existing read-only checks keep syscall 15 disabled.

```kotlin
val files = provideFiles(
    mapOf("input.ppm" to imageBytes),
    writablePaths = setOf("output.ppm"),
    maxReadSize = 7,
    maxWriteSize = 3,
    firstDescriptor = 9,
)
// Run the submitted function, then inspect its binary output and cleanup.
files.expectFile("output.ppm", expectedBytes)
files.expectAllClosed()
```

Only declared writable paths accept mode 1 (create/truncate) or mode 9 (create/append).
Mode 0 reads an existing fixture, including an output created earlier in that case.
A path can have only one open handle at a time. Handles are reused after close, and
`firstDescriptor` selects the lowest available handle from 3 through 31. Writing to
read handles, reading write handles, and using stdout/stderr as file-output handles
return errors. Output paths do not exist until successfully opened for writing.

`maxWriteSize` can force a short positive result. `failWriteAfterBytes` counts bytes
accepted on each opened writable descriptor; a preceding write stops exactly at the
failure count, and later nonempty writes return -1. Append mode counts newly written
bytes rather than the preexisting file length. `maxFileSize` defaults to 1 MiB per
output file; a write can fill the remaining space and further nonempty writes fail.
Empty writes return zero on an open writable handle, including at either limit.

`readFile(path)` returns a detached copy of current file bytes. `expectFile` compares
all bytes, including zeros and high-bit octets. Missing files fail the case. File
snapshots now include WRITE events with path, descriptor, requested count and result;
old snapshots remain detached from later operations. Each case starts with new files
and descriptor state.

The core write syscall currently reads one byte past its requested source interval.
The fixture records the environment's accepted count; it does not hide that simulated
memory access. A source buffer needs a readable guard byte after its payload. This
existing behavior is covered by `SyscallMemoryBoundaryTest`; the fixture change does
not alter the core syscall ABI.
