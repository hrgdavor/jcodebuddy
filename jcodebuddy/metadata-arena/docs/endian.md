# Endianness in metadata-arena

## Decision: Little-Endian

The `metadata-arena` module uses **little-endian** (native order on x86/AMD64) for all binary layouts, including the compact hash index and mmap file format.

## Rationale

### 1. SIMD-friendly element indexing

In little-endian SIMD (Intel AVX, ARM NEON/SVE), element 0 is always stored at byte offset 0 — both in memory and inside vector registers. This means:

- Byte 0 in memory → byte 0 in the vector register
- Integer 0 in a `4 × int32` vector starts at byte 0
- Long 0 in a `2 × int64` vector starts at byte 0

On big-endian, byte 0 corresponds to the most significant byte. When a register is reinterpreted as smaller element widths, the internal element positions shift relative to their memory addresses, forcing extra byte-swapping or index-reversing logic.

### 2. Zero-cost type reinterpretation (bitcasting)

A common SIMD pattern is reinterpreting vector types — for example, treating `<2 x i64>` as `<4 x i32>` or `<16 x i8>`.

- In little-endian: the lower bits of element `[0]` are always at index `[0]`. Narrowing or widening element widths does not move the base positions of elements.
- In big-endian: element `[0]`'s least significant bits sit at byte index 3 or 7. Changing vector element widths alters where the "first" piece of data resides, breaking simple pointer/register reinterpretation.

### 3. Shuffles and vector permutations

SIMD shuffle/permute instructions (x86 `pshufb`, ARM `tbl`) select elements using an index mask (`0, 1, 2, 3...`).

- In little-endian, lane 0 is the lowest offset, matching standard zero-indexed array math (`array[0]`).
- In big-endian, lane 0 is the highest offset, meaning shuffle masks must be mathematically inverted or reversed.

### 4. Historical precedent: IBM OpenPOWER

IBM's OpenPOWER introduced little-endian modes specifically because SIMD vector reformatting was so problematic on big-endian. IBM reported up to **65% speedup in vector kernels** simply by eliminating data reformatting primitives after switching to little-endian.

ARM is bi-endian by design, but all modern operating systems run it in little-endian mode because NEON/SVE vector units are optimized around little-endian layouts.

## Impact on metadata-arena

- `ByteBufferArena` and `FfmArena` default to `ByteOrder.LITTLE_ENDIAN`.
- `MemoryView` implementations (`ByteBufferMemoryView`, `FfmMemoryView`) respect the arena's byte order.
- `IndexMmapReader` reads mmap files using little-endian order.
- Cross-backend compatibility is preserved: both `ByteBuffer` (which defaults to big-endian) and FFM `ValueLayout` (which defaults to native/little-endian on x86) are explicitly configured to the same order.

## Future: SIMD acceleration

If future work adds SIMD-accelerated hash probes or batch value copies (e.g., via Panama Vector API or project Panama), little-endian layout eliminates byte-order correction overhead during bulk memory operations.
