# MARS textbook chapter ideas

Working outline for an assembly and computer architecture textbook taught
with the IntelliJ MARS plugin. Each larger project should ship with a tiny
fixture that reaches an interesting state in a few debugger steps and a full
fixture that produces an artifact or a measurable result. Use the same
`assignmentN.kts` checks for the corresponding registers, memory, and output.
For bounded assignments, give students a function contract and run the same
inputs through a C or Kotlin reference.

## 1. Hello MARS

| Chapter or lab | Exercise |
| --- | --- |
| Console introduction | Print a greeting, read an integer, and calculate a result. Step across the syscall and inspect its argument and result registers. |
| A single pixel | Write an RGB word to Bitmap Display. Change one byte and predict the affected channel. Use a source-header comment to configure the tool. |
| An eight-byte sprite | Draw an 8 × 8 sprite with one byte per row, then mirror it by reversing the order of the bits. Reuse the sprite in the image-format chapters. |
| Bit inspector | Print one word as hexadecimal, binary, signed decimal, and unsigned decimal. Trace sign extension across register widths. |
| Seven-segment counter | Count from 0 to F on the Digital Lab display with a lookup table, then design new glyphs. |
| MarsBot square | Drive the robot using memory-mapped commands. Change a heading and predict the stopping position. |

## 2. Algorithms students can watch

| Chapter or lab | Exercise |
| --- | --- |
| Bresenham line drawing | Follow the integer error accumulator while plotting a line on a tiny canvas. Test all slopes and both endpoint orders. |
| Cellular automata | Plot Rule 30 or Conway’s Game of Life one generation at a time. For Life, use separate source and destination buffers and swap them between generations. |
| Sorting visualizer | Animate swaps in insertion and selection sort. Compare instruction and branch counts on sorted, reversed, and shuffled inputs. |
| Maze explorer | Animate breadth-first search. Color the frontier, visited cells, and final path separately. |
| Matrix transpose | Implement ordinary and tiled transposition. Compare memory access patterns in the Data Cache Simulator and check that both outputs match. |
| Fixed-point bouncing ball | Represent position and velocity as scaled integers. Compare rounding rules and reproduce an overflow. |

## 3. C-style final exam assignments

| Assignment | Contract to grade |
| --- | --- |
| Binary packet parser | Validate a fixed header, payload length, and checksum. Return the number of bytes consumed or a specified error. Test partial inputs and malformed lengths. |
| Ring-buffer logger | Push and pop variable-length records. Handle independent wraparound, full and empty states, and exact-fit records. |
| UTF-8 scanner | Count code points and locate the first malformed byte sequence, including a sequence split across reads. |
| Clipped sprite blitter | Draw a transparent sprite into a strided framebuffer. Clip all four edges and verify guard words around the destination. |
| Expression evaluator | Handle precedence, parentheses, and unary minus. Define overflow and syntax errors in the contract. |
| Tiny bytecode interpreter | Run a stack machine with arithmetic and branches. Check stack bounds, jump targets, and invalid opcodes. |

## 4. Practical kernels and vector optimization

| Chapter or lab | Exercise |
| --- | --- |
| RGB888 and RGB565 | Convert between pixel formats. Use MSA to pack and unpack groups of pixels and inspect the resulting color loss. |
| PCM mixing | Mix signed 16-bit audio with saturating addition. Use a loud sample to make the difference between clipping and wraparound audible. |
| Fixed-point FIR filter | Filter a tone from a short audio clip. Verify an impulse response, then compare scalar and vector dot products. |
| Blocked matrix multiplication | Start with matrices students can check by hand. Compare simulated instruction counts and cache behavior as tile sizes change. |
| Delta and variable-length encoding | Compact sensor readings or timestamps. Compare the size of the result for steady values, spikes, and random input. |
| CRC-32 file checker | Implement bit-at-a-time and table-driven versions. Flip one input bit and follow the CRC state. Reuse the routine for PNG chunks. |
| SHA-256 fingerprint | Trace a compression round, check padding boundaries with test vectors, and compare a file digest against a reference. |
| ChaCha20 | Step through a quarter-round before implementing the block function. Use MSA to show what runs in parallel and where lane shuffles are needed. |
| RSA arithmetic | Begin with Euclid’s algorithm and square-and-multiply. Introduce multiword multiplication and carry propagation, then compare modular powers with `BigInteger.modPow`. Discuss standardized RSA padding and schemes after the arithmetic lab. |

## 5. Encoders, decoders, and capstones

| Chapter or lab | Exercise |
| --- | --- |
| Base64 | Encode and decode standard vectors, arbitrary lengths, and padding. Add a vector kernel for complete blocks and a scalar tail. |
| Binary PPM | Read a small image into the framebuffer and write an image another program can open. Introduce file boundaries, headers, and component order. |
| QOI | Reuse the eight-byte sprite as a real compressed image. Draw each pixel as the decoder reconstructs it. Add an encoder and compare runs and color differences on several image types. |
| PCM WAV filter | Read a WAV file, apply the mixer or FIR filter, and write a playable result. Specify which chunks the exercise supports and how it handles unexpected ones. |
| CBOR telemetry | Serialize and recover a sensor record using a subset of integers, byte strings, arrays, and maps. Include truncated and malformed input. |
| PNG | Teach chunk validation, DEFLATE, row reconstruction, and blitting separately. Use the existing scalar decoder as the reference. Vectorize RGB packing and the Up filter, then study Sub as an example of a dependency between reconstructed pixels. Compare exact output and simulated instructions per pixel. |
| Small lossless video codec | Encode a 64 × 64 animation with periodic full frames and intervening XOR deltas. Encode runs of unchanged pixels and apply updates in place. Measure bytes per frame and seek from a full frame. Vectorize XOR and change detection after the scalar version works. |
| APNG extension | Extend the PNG decoder with frame timing, rectangular updates, blending, and disposal. Use a short animation that can also be opened by an independent viewer. |

The [PNG smoke project](../examples/smoke/README.md#png-decoder-and-bitmap-display)
already provides the scalar/MSA comparison. At single-step speeds, use a tiny
input or run to a breakpoint at the routine under study. The full 512 × 512
fixture is suited to integration testing.
