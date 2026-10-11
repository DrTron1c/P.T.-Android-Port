#!/usr/bin/env python3
"""Builds small fake PS4 packages (fPKG) for PkgExtractorTest.java.

The layout follows LibOrbisPkg's builder (PkgBuilder.cs, PFSBuilder.cs, PFSCWriter.cs, Entry.cs, Crypto.cs), written
independently of the Java reader: AES-XTS comes from the `cryptography` package, not from the code under test. The packages hold
synthetic data only (random bytes with the magic numbers the extractor looks for), never any of the game.

    make_test_pkgs.py OUTDIR      writes OUTDIR/*.pkg and OUTDIR/manifest.txt
"""
import hashlib
import hmac
import os
import random
import struct
import sys
import zlib

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

BS = 0x10000
CONTENT_ID = "UP9000-CUSA01127_00-PTPTPTPTPTPTPT01"
PASSCODE = "0" * 32


# ---- keys (Crypto.cs) ----------------------------------------------------------------------------------------------------
def compute_keys(content_id, passcode, index):
    data = hashlib.sha256(struct.pack(">I", index)).digest()
    data += hashlib.sha256(content_id.encode("ascii").ljust(48, b"\0")).digest()
    data += passcode.encode("ascii")
    return hashlib.sha256(data).digest()


def pfs_enc_keys(ekpfs, seed, new_crypt):
    key = hmac.new(ekpfs, seed, hashlib.sha256).digest() if new_crypt else ekpfs
    enc = hmac.new(key, struct.pack("<I", 1) + seed, hashlib.sha256).digest()
    return enc[:16], enc[16:]  # tweak key, data key


def xts_encrypt_sector(data_key, tweak_key, number, plain):
    enc = Cipher(algorithms.AES(data_key + tweak_key), modes.XTS(struct.pack("<Q", number) + b"\0" * 8)).encryptor()
    return enc.update(plain) + enc.finalize()


# ---- PFS (PFSBuilder.cs) -------------------------------------------------------------------------------------------------
class Node:
    def __init__(self, name, data=None):
        self.name = name
        self.data = data          # bytes for a file, None for a directory
        self.children = []
        self.parent = None
        self.ino = None
        self.start = None
        self.blocks_list = None


def tree(spec, name="uroot", parent=None):
    """spec: {name: bytes | dict}"""
    node = Node(name)
    node.parent = parent
    for key, value in spec.items():
        if isinstance(value, dict):
            node.children.append(tree(value, key, node))
        else:
            child = Node(key, value)
            child.parent = node
            node.children.append(child)
    return node


def full_path(node):
    parts = []
    while node.parent is not None:
        parts.append(node.name)
        node = node.parent
    return "/".join(reversed(parts))


def all_nodes(root):
    dirs, files = [], []
    def walk(n):
        for c in n.children:
            (files if c.data is not None else dirs).append(c)
            if c.data is None:
                walk(c)
    walk(root)
    dirs.sort(key=full_path)
    files.sort(key=full_path)
    return dirs, files


def ceil_div(a, b):
    return (a + b - 1) // b


def dirent(ino, kind, name):
    raw = name.encode("ascii")
    ent = len(raw) + 17
    if ent % 8:
        ent += 8 - ent % 8
    return struct.pack("<IiII", ino, kind, len(raw), ent) + raw + b"\0" * (ent - 16 - len(raw))


def inode_bytes(signed, size, blocks, db, ib, is_dir):
    mode = 0x16D | (0x4000 if is_dir else 0x8000)
    head = struct.pack("<HHIqq", mode, 1, 0, size, size)
    head += struct.pack("<qqqq", 0, 0, 0, 0) + struct.pack("<IIII", 0, 0, 0, 0)
    head += struct.pack("<IIQQI", 0, 0, 0, 0, blocks)
    assert len(head) == 0x64
    if signed:
        body = b"".join(b"\0" * 32 + struct.pack("<i", b) for b in db + ib)
        assert len(body) == 17 * 36
    else:
        body = struct.pack("<12i", *db) + struct.pack("<5i", *ib)
    return head + body


def calc_ib(blocks):
    sigs = BS // 36
    n = 0
    if blocks > 12:
        blocks -= 12
        n += 1
    if blocks > sigs:
        blocks -= sigs
        n += 1 + ceil_div(blocks, sigs)
    return n


def build_pfs(spec, signed, encrypt=False, ekpfs=None, seed=None, new_crypt=False, fragment=()):
    """fragment: names of files whose data blocks are laid out out of order (a non-contiguous file)."""
    root = tree(spec)
    dirs, files = all_nodes(root)
    inodes = [("superroot",), ("fpt",), ("uroot", root)]
    root.ino = 2
    for d in dirs:
        d.ino = len(inodes)
        inodes.append(("dir", d))
    for f in files:
        f.ino = len(inodes)
        inodes.append(("file", f))
    n_inodes = len(inodes)
    dsize = 0x2C8 if signed else 0xA8
    dib = ceil_div(n_inodes, BS // dsize)

    def dir_blocks_bytes(d, parent_ino):
        entries = [dirent(d.ino, 4, "."), dirent(parent_ino, 5, "..")]
        for c in sorted(d.children, key=lambda c: (c.data is not None, c.name)):
            entries.append(dirent(c.ino, 2 if c.data is not None else 3, c.name))
        blob = b"".join(entries)
        assert len(blob) < BS - 280, "test directories stay within one block"
        return blob

    nodes = [root] + dirs + files
    superroot_block = dib + 1
    fpt_block = superroot_block + 1
    empty_block = fpt_block + 1
    next_block = empty_block + 1
    sigs = BS // 36
    ib_of = {}
    if signed:
        # indirect blocks come before the data blocks
        for n in nodes:
            size = BS if n.data is None else len(n.data)
            count = ceil_div(size, BS)
            ib_of[id(n)] = (next_block, calc_ib(count))
            next_block += calc_ib(count)
    for n in nodes:
        size = BS if n.data is None else len(n.data)
        count = max(1, ceil_div(size, BS))
        n.count = count
        n.start = next_block
        order = list(range(count))
        if n.name in fragment and count > 3:
            order = [i ^ 1 if (i ^ 1) < count else i for i in order]   # swap neighbours: not one run any more
        n.blocks_list = [n.start + i for i in order]
        next_block += count
    total_blocks = next_block

    image = bytearray(total_blocks * BS)
    # the superblock
    mode = (1 if signed else 0) | (4 if encrypt else 0) | 8
    hdr = struct.pack("<qqqBBBBHHIIqqqq", 1, 20130315, 0, 0, 0, 1, 0, mode, 0, BS, 0, 1, n_inodes, total_blocks, dib)
    image[0:len(hdr)] = hdr
    if signed or encrypt:
        image[0x36C:0x370] = struct.pack("<i", 1)
        image[0x370:0x380] = seed
    else:
        image[0x368:0x36C] = struct.pack("<i", 1)

    # the inodes
    records = []
    for ino_no, item in enumerate(inodes):
        kind = item[0]
        db = [0] * 12 if signed else [-1] * 12
        ib = [0] * 5 if signed else [-1] * 5
        if kind == "superroot":
            db[0] = superroot_block
            rec = inode_bytes(signed, BS, 1, db, ib, True)
        elif kind == "fpt":
            db[0] = fpt_block
            rec = inode_bytes(signed, 0x100, 1, db, ib, False)
        else:
            n = item[1]
            count = n.count
            size = BS if n.data is None else len(n.data)
            if signed:
                for i in range(min(12, count)):
                    db[i] = n.blocks_list[i]
                start_ib, n_ib = ib_of[id(n)]
                if count > 12:
                    ib[0] = start_ib
                if count > 12 + sigs:
                    ib[1] = start_ib + 1
            else:
                db[0] = n.blocks_list[0]
                for i in range(1, min(count, 12)):
                    db[i] = -1
            rec = inode_bytes(signed, size, count, db, ib, n.data is None)
        records.append(rec)
    per_block = BS // dsize
    for i, rec in enumerate(records):
        at = BS + (i // per_block) * BS + (i % per_block) * dsize
        image[at:at + dsize] = rec

    # the super root's dirents, the flat path table (zeros here), then every node's data
    blob = dirent(1, 2, "flat_path_table") + dirent(2, 3, "uroot")
    image[superroot_block * BS:superroot_block * BS + len(blob)] = blob
    for n in nodes:
        if n.data is None:
            parent_ino = n.parent.ino if n.parent is not None else n.ino
            blob = dir_blocks_bytes(n, parent_ino)
            at = n.blocks_list[0] * BS
            image[at:at + len(blob)] = blob
        else:
            for i in range(n.count):
                chunk = n.data[i * BS:(i + 1) * BS]
                at = n.blocks_list[i] * BS
                image[at:at + len(chunk)] = chunk

    if signed:
        # the indirect blocks: 36 byte records (32 byte signature, then the block number)
        for n in nodes:
            start_ib, n_ib = ib_of[id(n)]
            if n_ib == 0:
                continue
            count = n.count
            for i in range(12, min(count, 12 + sigs)):
                at = start_ib * BS + (i - 12) * 36 + 32
                image[at:at + 4] = struct.pack("<i", n.blocks_list[i])
            if count > 12 + sigs:
                dbl = start_ib + 1
                done = 12 + sigs
                nxt = dbl
                i = 0
                while done < count:
                    nxt += 1
                    image[dbl * BS + i * 36 + 32:dbl * BS + i * 36 + 36] = struct.pack("<i", nxt)
                    for j in range(sigs):
                        if done >= count:
                            break
                        at = nxt * BS + j * 36 + 32
                        image[at:at + 4] = struct.pack("<i", n.blocks_list[done])
                        done += 1
                    i += 1

    if encrypt:
        tweak_key, data_key = pfs_enc_keys(ekpfs, seed, new_crypt)
        for sector in range(16, len(image) // 0x1000):
            if sector // 16 == empty_block:
                continue
            at = sector * 0x1000
            image[at:at + 0x1000] = xts_encrypt_sector(data_key, tweak_key, sector, bytes(image[at:at + 0x1000]))
    return bytes(image)


# ---- PFSC (PFSCWriter.cs; the blocks of real packages are deflate compressed) -------------------------------------------------
def make_pfsc(data):
    n = ceil_div(len(data), BS)
    data = data + b"\0" * (n * BS - len(data))
    table = 8 + n * 8
    extra = ceil_div(max(0, table - 0xFC00), 0x10000)
    header_size = 0x10000 + extra * BS
    blocks = []
    for i in range(n):
        raw = data[i * BS:(i + 1) * BS]
        packed = zlib.compress(raw, 6)
        blocks.append(packed if len(packed) < BS else raw)
    offsets = [header_size]
    for b in blocks:
        offsets.append(offsets[-1] + len(b))
    head = b"PFSC" + struct.pack("<iiiqqqq", 0, 6, BS, BS, 0x400, header_size, n * BS)
    head += b"\0" * (0x400 - len(head))
    head += b"".join(struct.pack("<q", o) for o in offsets)
    head += b"\0" * (header_size - len(head))
    return head + b"".join(blocks)


# ---- the package (PkgBuilder.cs, Entry.cs) -------------------------------------------------------------------------------------
def build_pkg(inner_spec, content_id=CONTENT_ID, passcode=PASSCODE, outer_signed=True, new_crypt=False, fragment=(), rng=None):
    inner = build_pfs(inner_spec, signed=False)
    pfsc = make_pfsc(inner)
    ekpfs = compute_keys(content_id, passcode, 1)
    seed = rng.randbytes(16)
    outer = build_pfs({"pfs_image.dat": pfsc}, signed=outer_signed, encrypt=True, ekpfs=ekpfs, seed=seed, new_crypt=new_crypt, fragment=fragment)

    pkg = bytearray(0x80000 + len(outer))
    pkg[0:4] = b"\x7fCNT"
    pkg[0x10:0x14] = struct.pack(">I", 3)
    pkg[0x14:0x18] = struct.pack(">HH", 0, 3)
    pkg[0x18:0x1C] = struct.pack(">I", 0x2A80)
    pkg[0x40:0x40 + len(content_id)] = content_id.encode("ascii")
    pkg[0x404:0x408] = struct.pack(">I", 1)
    flags = 0x80000000000003CC | (0x2000000000000000 if new_crypt else 0)
    pkg[0x408:0x410] = struct.pack(">Q", flags)
    pkg[0x410:0x418] = struct.pack(">Q", 0x80000)
    pkg[0x418:0x420] = struct.pack(">Q", len(outer))
    # ENTRY_KEYS: the seed digest, 7 digests (SHA-256(key) xor key), 7 encrypted keys (random here)
    keys = bytearray(hashlib.sha256(content_id.encode("ascii").ljust(48, b"\0")).digest())
    for i in range(7):
        k = compute_keys(content_id, passcode, i)
        keys += bytes(a ^ b for a, b in zip(hashlib.sha256(k).digest(), k))
    for i in range(7):
        keys += rng.randbytes(256)
    assert len(keys) == 0x800
    pkg[0x2000:0x2800] = keys
    pkg[0x2800:0x2900] = rng.randbytes(256)
    entries = [(0x10, 0x2000, 0x800), (0x20, 0x2800, 0x100), (0x100, 0x2A80, 0x60)]
    for i, (eid, off, size) in enumerate(entries):
        pkg[0x2A80 + i * 0x20:0x2A80 + i * 0x20 + 32] = struct.pack(">IIIIII", eid, 0, 0, 0, off, size) + b"\0" * 8
    pkg[0x80000:] = outer
    return bytes(pkg)


# ---- synthetic game data -------------------------------------------------------------------------------------------------------
def noise(rng, n):
    return rng.randbytes(n)


def mixed(rng, n):
    """Half incompressible, half repetitive, so a package has both stored and deflated blocks."""
    out = bytearray()
    while len(out) < n:
        out += noise(rng, 40000) if rng.random() < 0.5 else (b"abcdefgh" * 9000)
    return bytes(out[:n])


def psarc(rng, n):
    return b"PSAR" + mixed(rng, n - 4)


def qar(rng, n):
    footer = bytearray(noise(rng, 0x24))
    footer[0x16] = ord("a")
    footer[0x17] = ord("q")
    return mixed(rng, n - 0x24) + bytes(footer)


def pathid(rng, pt=True):
    text = ("pt14_start\npt14_hallway\n" if pt else "other_game_level\n") * 2000
    return text.encode("ascii") + noise(rng, 64)


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    rng = random.Random(20260101)
    cases = []

    def add(name, pkg_bytes, outcome, expect, warn="-"):
        path = os.path.join(out, name + ".pkg")
        with open(path, "wb") as f:
            f.write(pkg_bytes)
        cases.append((name, name + ".pkg", outcome, expect, warn))

    def sums(files):
        return ";".join("%s=%s" % (k, hashlib.sha256(v).hexdigest()) for k, v in files.items())

    base = {"chunk1.psarc": psarc(rng, 1500000), "texture.qar": qar(rng, 3100000), "pathid_list_ps4.bin": pathid(rng)}
    spec = dict(base)
    spec["sce_sys"] = {"param.sfo": noise(rng, 300), "keystone": noise(rng, 96)}
    spec["Image0"] = {"decoy.psarc": psarc(rng, 100000)}
    ok = sums(base)

    add("standard", build_pkg(spec, rng=rng), "ok", ok)
    add("fragmented", build_pkg(spec, fragment=("pfs_image.dat",), rng=rng), "ok", ok)
    add("unsigned_outer", build_pkg(spec, outer_signed=False, rng=rng), "ok", ok)
    add("new_crypt", build_pkg(spec, new_crypt=True, rng=rng), "ok", ok)

    # the archives in a subfolder under other names, next to larger files that are not valid archives
    sub_psarc = psarc(rng, 900000)
    sub_qar = qar(rng, 1200000)
    sub = {"Image0": {"data": {"main.psarc": sub_psarc, "tex.qar": sub_qar, "huge.psarc": noise(rng, 2000000),
                               "pathid_list_all.bin": pathid(rng)}},
           "sce_sys": {"param.sfo": noise(rng, 300)}}
    add("subfolder", build_pkg(sub, rng=rng), "ok",
        sums({"chunk1.psarc": sub_psarc, "texture.qar": sub_qar, "pathid_list_ps4.bin": sub["Image0"]["data"]["pathid_list_all.bin"]}),
        "not at the package root")

    # an unknown title ID with P.T.'s content, a missing path list with a known title ID
    add("unknown_title", build_pkg(spec, content_id="UP0000-CUSA99999_00-ZZZZZZZZZZZZZZ01", rng=rng), "ok", ok, "not a known P.T. release")
    nopath = {"chunk1.psarc": base["chunk1.psarc"], "texture.qar": base["texture.qar"]}
    add("no_pathid", build_pkg(nopath, rng=rng), "ok", sums(nopath), "pathid_list_ps4.bin is missing")

    # refusals
    add("wrong_passcode", build_pkg(spec, passcode="1" * 32, rng=rng), "error", "store (PlayStation Store) package")
    other = {"chunk1.psarc": base["chunk1.psarc"], "texture.qar": base["texture.qar"], "pathid_list_ps4.bin": pathid(rng, pt=False)}
    add("other_game", build_pkg(other, content_id="UP0000-CUSA99999_00-ZZZZZZZZZZZZZZ01", rng=rng), "error", "does not hold P.T.")
    add("not_a_pkg", noise(rng, 20000), "error", "not a PS4 PKG")
    whole = build_pkg(spec, rng=rng)
    add("truncated", whole[:len(whole) // 2], "error", "Truncated PFS image")
    nothing = {"readme.txt": b"nothing here"}
    add("no_archives", build_pkg(nothing, rng=rng), "error", "does not hold P.T.")

    # past 12 + 1820 blocks a file uses a doubly indirect block: a 125 MB incompressible archive
    big_qar = noise(rng, 125 * 1000 * 1000 - 0x24)
    footer = bytearray(noise(rng, 0x24))
    footer[0x16] = ord("a")
    footer[0x17] = ord("q")
    big_qar += bytes(footer)
    bigspec = {"chunk1.psarc": base["chunk1.psarc"], "texture.qar": big_qar, "pathid_list_ps4.bin": base["pathid_list_ps4.bin"]}
    add("big", build_pkg(bigspec, rng=rng), "ok", sums(bigspec))

    with open(os.path.join(out, "manifest.txt"), "w") as f:
        for c in cases:
            f.write("|".join(c) + "\n")
    print("wrote %d packages to %s" % (len(cases), out))


if __name__ == "__main__":
    main()
