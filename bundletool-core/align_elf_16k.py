"""Rewrite ELF LOAD segments so their file offsets match a 16KB page size.

Keeps virtual addresses unchanged, so relocations stay valid. Idempotent when
the binary is already aligned.
"""

import struct
import sys

PT_LOAD = 1
ALIGN = 16384


def align_elf(data: bytes) -> bytes:
    if data[:4] != b"\x7fELF":
        raise SystemExit("not an ELF file")
    elf_class = data[4]
    if elf_class == 2:
        return _align(data, elf64=True)
    if elf_class == 1:
        return _align(data, elf64=False)
    raise SystemExit(f"unsupported ELF class {elf_class}")


def _align(data: bytes, elf64: bool) -> bytes:
    if elf64:
        e_phoff, e_shoff, e_phentsize, e_phnum, e_shentsize, e_shnum = _elf64_offsets(data)
        phoff_fmt = "<IIQQQQQQ"
    else:
        e_phoff, e_shoff, e_phentsize, e_phnum, e_shentsize, e_shnum = _elf32_offsets(data)
        phoff_fmt = "<IIIIIIII"

    phdrs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        phdrs.append(list(struct.unpack_from(phoff_fmt, data, off)))

    loads = [p for p in phdrs if p[0] == PT_LOAD]
    loads.sort(key=lambda p: p[2] if elf64 else p[1])

    insertions = []
    delta = 0
    for load in loads:
        if elf64:
            p_offset, p_vaddr = load[2], load[3]
        else:
            p_offset, p_vaddr = load[1], load[2]
        new_off = p_offset + delta
        pad = (p_vaddr - new_off) % ALIGN
        if pad:
            insertions.append((p_offset, pad))
            delta += pad
        if elf64:
            load[7] = max(load[7], ALIGN)
        else:
            load[7] = max(load[7], ALIGN)

    out = bytearray()
    cursor = 0
    for pos, pad in insertions:
        out += data[cursor:pos]
        out += b"\x00" * pad
        cursor = pos
    out += data[cursor:]

    def shift(off: int) -> int:
        extra = 0
        for pos, pad in insertions:
            if off >= pos:
                extra += pad
        return off + extra

    if elf64:
        struct.pack_into("<Q", out, 40, shift(e_shoff))
    else:
        struct.pack_into("<I", out, 32, shift(e_shoff))

    for i, phdr in enumerate(phdrs):
        if elf64:
            orig = struct.unpack_from(phoff_fmt, data, e_phoff + i * e_phentsize)[2]
            phdr[2] = shift(orig)
        else:
            orig = struct.unpack_from(phoff_fmt, data, e_phoff + i * e_phentsize)[1]
            phdr[1] = shift(orig)
        struct.pack_into(phoff_fmt, out, e_phoff + i * e_phentsize, *phdr)

    if e_shoff and e_shnum and e_shentsize:
        if elf64:
            sh_fmt_off = 24  # sh_offset is at +24 in Elf64_Shdr
        else:
            sh_fmt_off = 16
        new_shoff = shift(e_shoff)
        for i in range(e_shnum):
            sh = new_shoff + i * e_shentsize
            if elf64:
                sh_offset = struct.unpack_from("<Q", out, sh + sh_fmt_off)[0]
                struct.pack_into("<Q", out, sh + sh_fmt_off, shift(sh_offset))
            else:
                sh_offset = struct.unpack_from("<I", out, sh + sh_fmt_off)[0]
                struct.pack_into("<I", out, sh + sh_fmt_off, shift(sh_offset))

    _verify(out, elf64)
    return bytes(out)


def _elf64_offsets(data: bytes):
    e_phoff = struct.unpack_from("<Q", data, 32)[0]
    e_shoff = struct.unpack_from("<Q", data, 40)[0]
    e_phentsize = struct.unpack_from("<H", data, 54)[0]
    e_phnum = struct.unpack_from("<H", data, 56)[0]
    e_shentsize = struct.unpack_from("<H", data, 58)[0]
    e_shnum = struct.unpack_from("<H", data, 60)[0]
    return e_phoff, e_shoff, e_phentsize, e_phnum, e_shentsize, e_shnum


def _elf32_offsets(data: bytes):
    e_phoff = struct.unpack_from("<I", data, 28)[0]
    e_shoff = struct.unpack_from("<I", data, 32)[0]
    e_phentsize = struct.unpack_from("<H", data, 42)[0]
    e_phnum = struct.unpack_from("<H", data, 44)[0]
    e_shentsize = struct.unpack_from("<H", data, 46)[0]
    e_shnum = struct.unpack_from("<H", data, 48)[0]
    return e_phoff, e_shoff, e_phentsize, e_phnum, e_shentsize, e_shnum


def _verify(data: bytes, elf64: bool):
    if elf64:
        e_phoff, _, e_phentsize, e_phnum, _, _ = _elf64_offsets(data)
        fmt = "<IIQQQQQQ"
        off_i, addr_i, align_i = 2, 3, 7
    else:
        e_phoff, _, e_phentsize, e_phnum, _, _ = _elf32_offsets(data)
        fmt = "<IIIIIIII"
        off_i, addr_i, align_i = 1, 2, 7
    for i in range(e_phnum):
        ph = struct.unpack_from(fmt, data, e_phoff + i * e_phentsize)
        if ph[0] != PT_LOAD:
            continue
        if ph[align_i] < ALIGN or (ph[off_i] % ALIGN) != (ph[addr_i] % ALIGN):
            raise SystemExit(
                f"alignment failed off={ph[off_i]:#x} addr={ph[addr_i]:#x} align={ph[align_i]:#x}"
            )


def main(argv):
    for path in argv[1:]:
        original = open(path, "rb").read()
        updated = align_elf(original)
        if updated != original:
            open(path, "wb").write(updated)
            print(f"aligned {path}: {len(original)} -> {len(updated)}")
        else:
            print(f"already aligned {path}")


if __name__ == "__main__":
    main(sys.argv)
