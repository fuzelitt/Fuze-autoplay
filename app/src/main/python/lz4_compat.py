"""LZ4 block decompression that works with or without the `lz4` wheel.

The original project depends on the `lz4` C extension. If it is installed it is
used; otherwise a small pure-Python decoder takes over, so the app does not
depend on that wheel being available for Android.
"""


def _py_decompress(src, uncompressed_size=None):
    src = memoryview(src)
    n = len(src)
    out = bytearray()
    i = 0
    while i < n:
        token = src[i]
        i += 1

        lit = token >> 4
        if lit == 15:
            while True:
                b = src[i]
                i += 1
                lit += b
                if b != 255:
                    break
        if lit:
            out += src[i:i + lit]
            i += lit
        if i >= n:  # last sequence has literals only
            break

        offset = src[i] | (src[i + 1] << 8)
        i += 2
        if offset == 0 or offset > len(out):
            raise ValueError('invalid LZ4 match offset')

        mlen = token & 15
        if mlen == 15:
            while True:
                b = src[i]
                i += 1
                mlen += b
                if b != 255:
                    break
        mlen += 4

        start = len(out) - offset
        if offset >= mlen:
            out += out[start:start + mlen]
        else:
            # overlapping copy: repeat the pattern
            pattern = bytes(out[start:])
            reps, rem = divmod(mlen, offset)
            out += pattern * reps + pattern[:rem]

    if uncompressed_size is not None and len(out) != uncompressed_size:
        raise ValueError('LZ4 decompressed size mismatch')
    return bytes(out)


class _PyBlock:
    @staticmethod
    def decompress(data, uncompressed_size=None):
        return _py_decompress(data, uncompressed_size)


try:  # fast path
    import lz4.block as lz4_block
except Exception:  # pragma: no cover - depends on platform
    lz4_block = _PyBlock
