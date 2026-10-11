package com.ptport.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Takes the game archives out of a fake PS4 package (fPKG): chunk1.psarc, texture.qar and, if present, pathid_list_ps4.bin.
 *
 * This is the Java form of what the desktop installer's helper does with LibOrbisPkg (installer/Extractor/Program.cs, and
 * docs/installer.md for what it accepts and refuses), limited to the same thing: a fake package is the homebrew container,
 * encrypted with keys that follow from the package's own content ID and the standard passcode (32 zeros). A store (retail)
 * package is encrypted for the console that holds its license; its keys do not follow from anything in the file, the check
 * below fails and the package is refused, as the desktop installer refuses it.
 *
 * Layers, outermost first:
 *   PKG file -> (pfs_image_offset, pfs_image_size) outer PFS image, AES-XTS encrypted from its sector 16 on
 *   -> its file pfs_image.dat is a PFSC (block compressed) container
 *   -> the inner PFS image: the game's files.
 *
 * Uses only java.* and javax.crypto, so it can be run and tested on a desktop JDK (tools/android/pkgtest).
 */
final class PkgExtractor {
    /** Random access to bytes. */
    interface Source {
        long length();

        void read(long pos, byte[] buf, int off, int len) throws IOException;
    }

    interface Progress {
        void status(String message);

        void bytes(long done, long total);
    }

    /** A problem with the package itself; the message is for the player. */
    static final class PkgException extends IOException {
        private static final long serialVersionUID = 1L;

        PkgException(String message) {
            super(message);
        }
    }

    /** The bytes are not the structure they should be (what a wrong key or a damaged file looks like); an ordinary read error is a plain IOException. */
    static final class FormatException extends IOException {
        private static final long serialVersionUID = 1L;

        FormatException(String message) {
            super(message);
        }
    }

    static final class Result {
        String contentId = "";
        String titleId = "";
        String region = "unknown";
        final List<String> warnings = new ArrayList<String>();
        final List<String> written = new ArrayList<String>();
    }

    static final String NOT_PKG_MESSAGE = "This file is not a PS4 PKG.";
    static final String RETAIL_MESSAGE = "This PKG is a store (PlayStation Store) package, a fake package made with a different "
            + "passcode, or it is damaged. Store packages are encrypted for the console that owns the license, and this app does "
            + "not decrypt them. Dump P.T. from your own PS4 and choose the dumped game folder, or a fake PKG (fPKG) built from "
            + "that dump with the standard passcode.";
    static final String NOT_PT_MESSAGE = "This package does not hold P.T.'s game data (no archive pair with P.T.'s paths). "
            + "Choose the P.T. game package or your dumped game folder.";

    private static final String[] REQUIRED_NAMES = { "chunk1.psarc", "texture.qar", "pathid_list_ps4.bin" };
    // P.T. releases by title ID; every one is accepted, an unknown ID too when the content is P.T.
    private static final Map<String, String> RELEASES = new HashMap<String, String>();

    static {
        RELEASES.put("CUSA01127", "US");
        RELEASES.put("CUSA01114", "Europe");
        RELEASES.put("CUSA01098", "Japan");
    }

    private static final String STANDARD_PASSCODE = "00000000000000000000000000000000";
    private static final long MAX_ARCHIVE_BYTES = 16L << 30;

    private PkgExtractor() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // entry point

    static Result extract(Source pkg, File destination, Progress progress) throws IOException {
        try {
            return extractImpl(pkg, destination, progress);
        } catch (GeneralSecurityException e) {
            throw new IOException("cryptography failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // an index or size read from a damaged file that points outside its data
            throw new PkgException("The package is damaged (" + e + ").");
        }
    }

    private static Result extractImpl(Source pkg, File destination, Progress progress) throws IOException, GeneralSecurityException {
        final long pkgLength = pkg.length();
        if (pkgLength < 0x1000) throw new PkgException(NOT_PKG_MESSAGE);
        byte[] head = new byte[0x1000];
        pkg.read(0, head, 0, head.length);
        if ((head[0] & 0xff) != 0x7f || head[1] != 'C' || head[2] != 'N' || head[3] != 'T') throw new PkgException(NOT_PKG_MESSAGE);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN);

        long entryCount = h.getInt(0x10) & 0xffffffffL;
        long entryTable = h.getInt(0x18) & 0xffffffffL;
        String contentId = cstring(head, 0x40, 0x30);
        long pfsFlags = h.getLong(0x408);
        long pfsOffset = h.getLong(0x410);
        long pfsSize = h.getLong(0x418);

        Result result = new Result();
        result.contentId = contentId;
        result.titleId = contentId.length() >= 16 ? contentId.substring(7, 16) : "";

        // The entry table: 0x20 bytes per entry, big endian: id, name offset, flags 1, flags 2, data offset, data size, padding.
        if (entryCount == 0 || entryCount > 4096 || entryTable + entryCount * 0x20 > pkgLength) throw new PkgException(RETAIL_MESSAGE);
        byte[] table = new byte[(int) (entryCount * 0x20)];
        pkg.read(entryTable, table, 0, table.length);
        ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.BIG_ENDIAN);
        long keysOffset = -1;
        long keysSize = 0;
        for (int i = 0; i < entryCount; ++i) {
            if (t.getInt(i * 0x20) == 0x0010) {   // ENTRY_KEYS: a seed digest, then 7 key digests, then 7 encrypted keys
                keysOffset = t.getInt(i * 0x20 + 16) & 0xffffffffL;
                keysSize = t.getInt(i * 0x20 + 20) & 0xffffffffL;
                break;
            }
        }
        if (keysOffset < 0 || keysSize < 0x40 + 0x20 || keysOffset + 0x40 + 0x20 > pkgLength) throw new PkgException(RETAIL_MESSAGE);

        // The PFS key (EKPFS) of a fake package is key number 1 derived from the content ID and the standard passcode; the key's
        // digest in ENTRY_KEYS tells whether that is the key this package was made with.
        byte[] digest1 = new byte[32];
        pkg.read(keysOffset + 0x20 + 0x20, digest1, 0, 32);
        byte[] ekpfs = computeKeys(contentId, STANDARD_PASSCODE, 1);
        byte[] check = sha256(ekpfs);
        for (int i = 0; i < 32; ++i) check[i] ^= ekpfs[i];
        if (!Arrays.equals(check, digest1)) throw new PkgException(RETAIL_MESSAGE);

        if (pfsOffset < 0 || pfsSize <= 0 || pfsOffset > pkgLength || pfsSize > pkgLength - pfsOffset) throw new PkgException("Truncated PFS image.");
        progress.status("Reading the package...");
        Source image = new OffsetSource(pkg, pfsOffset, pfsSize);
        Pfs outer;
        Pfs inner;
        try {
            outer = new Pfs(image, pfsFlags, ekpfs);
            Pfs.File innerFile = outer.find("pfs_image.dat");
            if (innerFile == null) throw new PkgException(RETAIL_MESSAGE);
            inner = new Pfs(new Pfsc(outer.open(innerFile)), 0, null);
        } catch (FormatException e) {
            // a wrong key shows up as garbage in the PFS structures
            throw new PkgException(RETAIL_MESSAGE);
        }

        Pfs.Dir root = inner.uroot;
        List<Pfs.File> files = new ArrayList<Pfs.File>();
        collect(root, files);
        Pfs.File psarc = choose(files, root, result.warnings, "chunk1.psarc", ".psarc", 0);
        Pfs.File qar = choose(files, root, result.warnings, "texture.qar", ".qar", 1);
        Pfs.File pathid = choose(files, root, result.warnings, "pathid_list_ps4.bin", "pathid_list", 2);
        // choose() validated by content with the readers of its own pfs
        if (psarc == null || qar == null) throw new PkgException(NOT_PT_MESSAGE);

        byte[] pathidBytes = new byte[0];
        if (pathid != null && pathid.size < (64L << 20)) {
            pathidBytes = new byte[(int) pathid.size];
            inner.open(pathid).read(0, pathidBytes, 0, pathidBytes.length);
        }
        if (!looksLikePt(pathidBytes, result.titleId)) throw new PkgException(NOT_PT_MESSAGE);
        String region = RELEASES.get(result.titleId);
        if (region == null) {
            result.warnings.add("content ID " + contentId + " is not a known P.T. release; used because its archives are P.T.'s");
        } else {
            result.region = region;
            if (!"CUSA01127".equals(result.titleId)) {
                result.warnings.add(result.titleId + " is the " + region + " release; the port is tested with the US release CUSA01127");
            }
        }
        if (pathid == null) result.warnings.add("pathid_list_ps4.bin is missing; the game runs without it");

        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("cannot create " + destination);
        String[] names = REQUIRED_NAMES;
        Pfs.File[] chosen = { psarc, qar, pathid };
        long total = 0;
        for (Pfs.File file : chosen) {
            if (file == null) continue;
            if (file.size < 0 || file.size > MAX_ARCHIVE_BYTES) throw new PkgException("Unexpected archive size.");
            total += file.size;
        }
        if (destination.getUsableSpace() < total + (64L << 20)) {
            throw new PkgException(String.format(Locale.US, "Not enough free space: the files take %.1f GB.", total / 1073741824.0));
        }
        long done = 0;
        byte[] buffer = new byte[1 << 20];
        for (int i = 0; i < chosen.length; ++i) {
            Pfs.File file = chosen[i];
            if (file == null) continue;
            progress.status("Extracting " + names[i]);
            Source view = inner.open(file);
            File target = new File(destination, names[i]);
            File part = new File(destination, names[i] + ".part");
            OutputStream out = new FileOutputStream(part);
            try {
                for (long pos = 0; pos < file.size; ) {
                    int count = (int) Math.min(buffer.length, file.size - pos);
                    view.read(pos, buffer, 0, count);
                    out.write(buffer, 0, count);
                    pos += count;
                    done += count;
                    progress.bytes(done, total);
                }
            } finally {
                out.close();
            }
            if (target.exists() && !target.delete()) throw new IOException("cannot replace " + names[i]);
            if (!part.renameTo(target)) throw new IOException("cannot finish " + names[i]);
            result.written.add(names[i]);
        }
        // what the install was made from; the game logs it at start (src/engine/fs/vfs.cpp)
        StringBuilder text = new StringBuilder();
        text.append("content_id=").append(contentId).append('\n');
        text.append("title_id=").append(result.titleId).append('\n');
        text.append("region=").append(result.region).append('\n');
        text.append("source=pkg\n");
        for (String warning : result.warnings) text.append("warning=").append(warning).append('\n');
        OutputStream info = new FileOutputStream(new File(destination, "source.txt"));
        try {
            info.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            info.close();
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // recognising the archives

    /** Files of the image, not those under sce_sys or sce_module. */
    private static void collect(Pfs.Dir dir, List<Pfs.File> out) {
        for (Pfs.Node node : dir.children) {
            if (node instanceof Pfs.File) out.add((Pfs.File) node);
            else collect((Pfs.Dir) node, out);
        }
    }

    private static boolean isPsarc(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'S' && head[2] == 'A' && head[3] == 'R';
    }

    private static boolean isQarFooter(byte[] footer) {
        return footer.length == 0x24 && footer[0x16] == 'a' && footer[0x17] == 'q';
    }

    /** kind 0: a PSARC (starts with PSAR), 1: a QAR (footer), 2: a path list (any file of at least 32 bytes). */
    private static boolean valid(Pfs pfs, Pfs.File file, int kind) {
        try {
            if (kind == 2) return file.size >= 32;
            if (kind == 0) {
                if (file.size < 4) return false;
                byte[] head = new byte[4];
                pfs.open(file).read(0, head, 0, 4);
                return isPsarc(head);
            }
            if (file.size < 0x24) return false;
            byte[] footer = new byte[0x24];
            pfs.open(file).read(file.size - 0x24, footer, 0, 0x24);
            return isQarFooter(footer);
        } catch (IOException e) {
            return false;
        }
    }

    // the Pfs the files belong to is found through the node
    private static Pfs.File choose(List<Pfs.File> files, Pfs.Dir root, List<String> warnings, String name, String kindSuffix, int kind) {
        for (Pfs.File f : files) {
            if (f.name.equals(name) && f.parent == root && valid(f.pfs, f, kind)) return f;
        }
        Pfs.File best = null;
        for (Pfs.File f : files) {
            String lower = f.name.toLowerCase(Locale.ROOT);
            boolean ofKind = kind == 2 ? lower.contains(kindSuffix) : lower.endsWith(kindSuffix);
            if (!ofKind || !valid(f.pfs, f, kind)) continue;
            if (best == null || f.size > best.size) best = f;
        }
        if (best != null) warnings.add(name + " not at the package root; using " + best.fullName());
        return best;
    }

    private static boolean looksLikePt(byte[] pathidList, String title) {
        return indexOf(pathidList, "pt14_".getBytes(StandardCharsets.US_ASCII)) >= 0 || RELEASES.containsKey(title);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; ++i) {
            for (int j = 0; j < needle.length; ++j) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // keys

    static String cstring(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) ++end;
        return new String(b, off, end - off, StandardCharsets.US_ASCII);
    }

    static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /**
     * The package key with the given index (LibOrbisPkg Crypto.ComputeKeys): SHA-256 of the SHA-256 of the index (4 bytes, big
     * endian), the SHA-256 of the content ID padded with zeros to 48 bytes, and the 32 character passcode. Index 1 is the PFS key.
     */
    static byte[] computeKeys(String contentId, String passcode, int index) throws GeneralSecurityException {
        byte[] data = new byte[96];
        byte[] indexBytes = { (byte) (index >>> 24), (byte) (index >>> 16), (byte) (index >>> 8), (byte) index };
        System.arraycopy(sha256(indexBytes), 0, data, 0, 32);
        byte[] id = new byte[48];
        byte[] raw = contentId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, id, 0, Math.min(raw.length, 48));
        System.arraycopy(sha256(id), 0, data, 32, 32);
        byte[] code = passcode.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(code, 0, data, 64, Math.min(code.length, 32));
        return sha256(data);
    }

    /** HMAC-SHA256 with the key, over the index (4 bytes, little endian) and the seed. */
    private static byte[] pfsCryptoKey(byte[] key, byte[] seed, int index) throws GeneralSecurityException {
        byte[] data = new byte[4 + seed.length];
        data[0] = (byte) index;
        data[1] = (byte) (index >>> 8);
        data[2] = (byte) (index >>> 16);
        data[3] = (byte) (index >>> 24);
        System.arraycopy(seed, 0, data, 4, seed.length);
        return hmacSha256(key, data);
    }

    /** The XTS tweak key and data key of an encrypted PFS image, from the PFS key and the seed in its header. */
    static byte[][] pfsEncKeys(byte[] ekpfs, byte[] seed, boolean newCrypt) throws GeneralSecurityException {
        byte[] enc = pfsCryptoKey(newCrypt ? hmacSha256(ekpfs, seed) : ekpfs, seed, 1);
        return new byte[][] { Arrays.copyOfRange(enc, 0, 16), Arrays.copyOfRange(enc, 16, 32) };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // byte sources

    /** A window of another source. */
    static final class OffsetSource implements Source {
        private final Source base;
        private final long start;
        private final long size;

        OffsetSource(Source base, long start, long size) {
            this.base = base;
            this.start = start;
            this.size = size;
        }

        @Override
        public long length() {
            return size;
        }

        @Override
        public void read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > size) throw new IOException("read beyond the end of the data");
            base.read(start + pos, buf, off, len);
        }
    }

    /** A file spread over blocks that are not in one run. */
    static final class BlockMapSource implements Source {
        private final Source base;
        private final int blockSize;
        private final int[] blocks;
        private final long size;

        BlockMapSource(Source base, int blockSize, int[] blocks, long size) {
            this.base = base;
            this.blockSize = blockSize;
            this.blocks = blocks;
            this.size = size;
        }

        @Override
        public long length() {
            return size;
        }

        @Override
        public void read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > size) throw new IOException("read beyond the end of the file");
            while (len > 0) {
                int index = (int) (pos / blockSize);
                int within = (int) (pos - (long) index * blockSize);
                int count = Math.min(len, blockSize - within);
                base.read((long) blocks[index] * blockSize + within, buf, off, count);
                pos += count;
                off += count;
                len -= count;
            }
        }
    }

    /** A source on a file channel (positional reads). */
    static Source ofChannel(final FileChannel channel) throws IOException {
        final long size = channel.size();
        return new Source() {
            @Override
            public long length() {
                return size;
            }

            @Override
            public void read(long pos, byte[] buf, int off, int len) throws IOException {
                ByteBuffer target = ByteBuffer.wrap(buf, off, len);
                while (target.hasRemaining()) {
                    int read = channel.read(target, pos + target.position() - off);
                    if (read < 0) throw new IOException("unexpected end of the file");
                }
            }
        };
    }

    // ---------------------------------------------------------------------------------------------------------------
    // AES-XTS (IEEE 1619) with 128-bit keys, the little endian sector number as tweak

    static final class Xts {
        private final Cipher data;
        private final Cipher tweak;
        private byte[] tweakIn = new byte[0];
        private byte[] tweakOut = new byte[0];
        private byte[] tweakStream = new byte[0];
        private byte[] scratch = new byte[0];

        Xts(byte[] dataKey, byte[] tweakKey) throws GeneralSecurityException {
            data = Cipher.getInstance("AES/ECB/NoPadding");
            data.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"));
            tweak = Cipher.getInstance("AES/ECB/NoPadding");
            tweak.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tweakKey, "AES"));
        }

        /** Decrypts count sectors of sectorSize bytes in place; the first one has number firstSector. */
        void decrypt(byte[] buf, int off, int count, int sectorSize, long firstSector) throws GeneralSecurityException {
            final int total = count * sectorSize;
            if (tweakIn.length < count * 16) {
                tweakIn = new byte[count * 16];
                tweakOut = new byte[count * 16];
            }
            if (tweakStream.length < total) {
                tweakStream = new byte[total];
                scratch = new byte[total];
            }
            // the tweak of each sector: its number, 8 bytes little endian, 8 zero bytes, encrypted with the tweak key
            Arrays.fill(tweakIn, 0, count * 16, (byte) 0);
            for (int s = 0; s < count; ++s) {
                long number = firstSector + s;
                for (int b = 0; b < 8; ++b) tweakIn[s * 16 + b] = (byte) (number >>> (8 * b));
            }
            tweak.doFinal(tweakIn, 0, count * 16, tweakOut, 0);
            // the tweak of each 16 byte block of the sector: the sector's tweak times x^n in GF(2^128)
            for (int s = 0; s < count; ++s) {
                long lo = le64(tweakOut, s * 16);
                long hi = le64(tweakOut, s * 16 + 8);
                int at = s * sectorSize;
                for (int b = 0; b < sectorSize; b += 16) {
                    put64(tweakStream, at + b, lo);
                    put64(tweakStream, at + b + 8, hi);
                    long carry = hi >>> 63;
                    hi = (hi << 1) | (lo >>> 63);
                    lo <<= 1;
                    if (carry != 0) lo ^= 0x87L;
                }
            }
            // plaintext = D(cipher ^ tweak) ^ tweak
            for (int i = 0; i < total; ++i) scratch[i] = (byte) (buf[off + i] ^ tweakStream[i]);
            data.doFinal(scratch, 0, total, scratch, 0);
            for (int i = 0; i < total; ++i) buf[off + i] = (byte) (scratch[i] ^ tweakStream[i]);
        }
    }

    private static long le64(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; --i) v = (v << 8) | (b[at + i] & 0xff);
        return v;
    }

    private static void put64(byte[] b, int at, long v) {
        for (int i = 0; i < 8; ++i) b[at + i] = (byte) (v >>> (8 * i));
    }

    /** Decrypting view of a PFS image: sectors from startSector on are encrypted, the ones before are not. */
    static final class XtsSource implements Source {
        private static final int SECTOR = 0x1000;
        private final Source base;
        private final Xts xts;
        private final long startSector;
        private final byte[] cache = new byte[SECTOR];
        private long cachedSector = -1;
        private final byte[] bulk = new byte[SECTOR * 64];

        XtsSource(Source base, byte[] dataKey, byte[] tweakKey, long startSector) throws GeneralSecurityException {
            this.base = base;
            this.xts = new Xts(dataKey, tweakKey);
            this.startSector = startSector;
        }

        @Override
        public long length() {
            return base.length();
        }

        private void loadSector(long sector) throws IOException {
            if (cachedSector == sector) return;
            readSectors(sector, 1, cache, 0);
            cachedSector = sector;
        }

        private void readSectors(long first, int count, byte[] out, int off) throws IOException {
            base.read(first * SECTOR, out, off, count * SECTOR);
            // the sectors before startSector are not encrypted
            long firstEncrypted = Math.max(first, startSector);
            int skip = (int) Math.min(count, firstEncrypted - first);
            if (skip < count) {
                try {
                    xts.decrypt(out, off + skip * SECTOR, count - skip, SECTOR, first + skip);
                } catch (GeneralSecurityException e) {
                    throw new IOException("decryption failed: " + e.getMessage(), e);
                }
            }
        }

        @Override
        public void read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > base.length()) throw new IOException("read beyond the end of the image");
            while (len > 0) {
                long sector = pos / SECTOR;
                int within = (int) (pos - sector * SECTOR);
                if (within == 0 && len >= SECTOR) {
                    // whole sectors in one go
                    int sectors = Math.min(len / SECTOR, 64);
                    readSectors(sector, sectors, bulk, 0);
                    System.arraycopy(bulk, 0, buf, off, sectors * SECTOR);
                    int bytes = sectors * SECTOR;
                    pos += bytes;
                    off += bytes;
                    len -= bytes;
                } else {
                    loadSector(sector);
                    int count = Math.min(len, SECTOR - within);
                    System.arraycopy(cache, within, buf, off, count);
                    pos += count;
                    off += count;
                    len -= count;
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // PFSC: a file in blocks that may be deflate compressed

    static final class Pfsc implements Source {
        private final Source base;
        private final int blockSize;
        private final long dataLength;
        private final long[] map;
        private final byte[] block;
        private long cachedBlock = -1;
        private final Inflater inflater = new Inflater(true);
        private byte[] compressed = new byte[0];

        Pfsc(Source base) throws IOException {
            this.base = base;
            byte[] h = new byte[0x30];
            base.read(0, h, 0, h.length);
            ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x43534650) throw new FormatException("not a PFSC image (no PFSC magic)");
            if (b.getInt(4) != 0) throw new FormatException("not a PFSC image");
            blockSize = b.getInt(0x0c);
            long blockSize2 = b.getLong(0x10);
            long mapOffset = b.getLong(0x18);
            dataLength = b.getLong(0x28);
            if (blockSize != blockSize2 || blockSize < 0x1000 || blockSize > (1 << 24)) throw new FormatException("not a PFSC image (block size)");
            long blocks = dataLength / blockSize;
            if (dataLength < 0 || blocks > (1 << 22) || mapOffset < 0x30) throw new FormatException("not a PFSC image (size)");
            byte[] raw = new byte[(int) ((blocks + 1) * 8)];
            base.read(mapOffset, raw, 0, raw.length);
            map = new long[(int) blocks + 1];
            for (int i = 0; i < map.length; ++i) map[i] = le64(raw, i * 8);
            block = new byte[blockSize];
        }

        @Override
        public long length() {
            return dataLength;
        }

        private void loadBlock(long index) throws IOException {
            if (cachedBlock == index) return;
            if (index < 0 || index >= map.length - 1) throw new FormatException("PFSC block out of range");
            long offset = map[(int) index];
            long size = map[(int) index + 1] - offset;
            if (size == blockSize) {
                base.read(offset, block, 0, blockSize);
            } else if (size > blockSize || size <= 2) {
                Arrays.fill(block, (byte) 0);
            } else {
                if (compressed.length < size) compressed = new byte[(int) size];
                base.read(offset, compressed, 0, (int) size);
                // two bytes of zlib header, then the deflate stream
                inflater.reset();
                inflater.setInput(compressed, 2, (int) size - 2);
                int filled = 0;
                try {
                    while (filled < blockSize && !inflater.finished()) {
                        int n = inflater.inflate(block, filled, blockSize - filled);
                        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                        filled += n;
                    }
                } catch (DataFormatException e) {
                    throw new FormatException("corrupt compressed block " + index);
                }
                if (filled < blockSize) Arrays.fill(block, filled, blockSize, (byte) 0);
            }
            cachedBlock = index;
        }

        @Override
        public void read(long pos, byte[] buf, int off, int len) throws IOException {
            if (pos < 0 || len < 0 || pos + len > dataLength) throw new IOException("read beyond the end of the PFSC data");
            while (len > 0) {
                long index = pos / blockSize;
                int within = (int) (pos - index * blockSize);
                loadBlock(index);
                int count = Math.min(len, blockSize - within);
                System.arraycopy(block, within, buf, off, count);
                pos += count;
                off += count;
                len -= count;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // PFS: the file system image (superblock, inodes, directories of dirents, data in blocks)

    static final class Pfs {
        abstract static class Node {
            Dir parent;
            String name;
            Pfs pfs;

            String fullName() {
                return parent != null && parent.name != null ? parent.fullName() + "/" + name : name;
            }
        }

        static final class Dir extends Node {
            final List<Node> children = new ArrayList<Node>();
        }

        static final class File extends Node {
            int ino;
            long size;
        }

        private static final class Ino {
            long size;
            long blocks;
            int[] db = new int[12];
            int[] ib = new int[5];
        }

        private static final int MAX_DEPTH = 40;

        private final Source reader;
        private final int blockSize;
        private final boolean signed;
        private final Ino[] inodes;
        Dir uroot;

        Pfs(Source image, long pfsFlags, byte[] ekpfs) throws IOException, GeneralSecurityException {
            byte[] head = new byte[0x400];
            if (image.length() < head.length) throw new FormatException("PFS image too small");
            image.read(0, head, 0, head.length);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            if (h.getLong(0) != 1 || h.getLong(8) != 20130315L) throw new FormatException("not a PFS image (superblock)");
            int mode = h.getShort(0x1c) & 0xffff;
            long bs = h.getInt(0x20) & 0xffffffffL;
            long dinodeCount = h.getLong(0x30);
            long dinodeBlocks = h.getLong(0x40);
            byte[] seed = Arrays.copyOfRange(head, 0x370, 0x380);
            if (bs < 0x1000 || bs > (1 << 22) || (bs & (bs - 1)) != 0) throw new FormatException("not a PFS image (block size)");
            blockSize = (int) bs;
            signed = (mode & 0x1) != 0;
            boolean encrypted = (mode & 0x4) != 0;
            final int dinodeSize = signed ? 0x2C8 : 0xA8;
            if (dinodeCount < 1 || dinodeCount > (1 << 22) || dinodeBlocks < 1 || dinodeBlocks > (1 << 22)) throw new FormatException("not a PFS image (inode counts)");

            if (encrypted) {
                if (ekpfs == null) throw new IOException("PFS image is encrypted but no key was given");
                byte[][] keys = pfsEncKeys(ekpfs, seed, (pfsFlags & 0x2000000000000000L) != 0);
                reader = new XtsSource(image, keys[1], keys[0], blockSize / 0x1000);
            } else {
                reader = image;
            }

            inodes = new Ino[(int) dinodeCount];
            byte[] block = new byte[blockSize];
            int perBlock = blockSize / dinodeSize;
            int total = 0;
            for (long i = 0; i < dinodeBlocks && total < dinodeCount; ++i) {
                reader.read(blockSize + blockSize * i, block, 0, blockSize);
                ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
                for (int j = 0; j < perBlock && total < dinodeCount; ++j) {
                    inodes[total++] = readInode(b, j * dinodeSize);
                }
            }
            if (total < dinodeCount) throw new FormatException("not a PFS image (missing inodes)");

            Dir superRoot = loadDir(0, null, null, 0);
            Node found = null;
            for (Node n : superRoot.children) {
                if ("uroot".equals(n.name) && n instanceof Dir) found = n;
            }
            if (found == null) throw new FormatException("invalid PFS image (no uroot)");
            uroot = (Dir) found;
        }

        private Ino readInode(ByteBuffer b, int at) {
            Ino ino = new Ino();
            ino.size = b.getLong(at + 8);
            ino.blocks = b.getInt(at + 0x60) & 0xffffffffL;
            if (signed) {
                for (int i = 0; i < 12; ++i) ino.db[i] = b.getInt(at + 0x64 + 36 * i + 32);
                for (int i = 0; i < 5; ++i) ino.ib[i] = b.getInt(at + 0x64 + 36 * 12 + 36 * i + 32);
            } else {
                for (int i = 0; i < 12; ++i) ino.db[i] = b.getInt(at + 0x64 + 4 * i);
                for (int i = 0; i < 5; ++i) ino.ib[i] = b.getInt(at + 0x64 + 48 + 4 * i);
            }
            return ino;
        }

        /**
         * Reads a directory's dirents and, below it, its subdirectories (sce_sys and sce_module are skipped, and so is everything
         * but uroot in the super root, which also holds flat_path_table and collision_resolver).
         */
        private Dir loadDir(int inoNumber, Dir parent, String name, int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new FormatException("directories nested too deep");
            if (inoNumber < 0 || inoNumber >= inodes.length) throw new FormatException("invalid PFS image (inode number)");
            Ino ino = inodes[inoNumber];
            if (ino.blocks < 1 || ino.blocks > (1 << 20) || ino.db[0] < 1) throw new FormatException("inode " + inoNumber + " is corrupt");
            Dir dir = new Dir();
            dir.parent = parent;
            dir.name = name;
            dir.pfs = this;
            List<Integer> subdirs = new ArrayList<Integer>();
            List<String> subdirNames = new ArrayList<String>();
            byte[] block = new byte[blockSize];
            for (long x = ino.db[0]; x < ino.db[0] + ino.blocks; ++x) {
                reader.read(x * blockSize, block, 0, blockSize);
                ByteBuffer b = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
                int p = 0;
                while (p + 16 <= blockSize) {
                    int entryIno = b.getInt(p);
                    int type = b.getInt(p + 4);
                    int nameLength = b.getInt(p + 8);
                    int entSize = b.getInt(p + 12);
                    if (entSize == 0) break;
                    if (entSize < 16 || p + entSize > blockSize || nameLength < 0 || 16 + nameLength > entSize) throw new FormatException("invalid PFS directory");
                    String entryName = cstring(block, p + 16, nameLength);
                    if (type == 2) {   // a file
                        if (entryIno < 0 || entryIno >= inodes.length) throw new FormatException("invalid PFS image (file inode)");
                        File file = new File();
                        file.name = entryName;
                        file.parent = dir;
                        file.pfs = this;
                        file.ino = entryIno;
                        file.size = inodes[entryIno].size;
                        dir.children.add(file);
                    } else if (type == 3) {   // a directory
                        subdirs.add(entryIno);
                        subdirNames.add(entryName);
                    }
                    p += entSize;
                }
            }
            for (int i = 0; i < subdirs.size(); ++i) {
                String sub = subdirNames.get(i);
                if (sub.equals("sce_sys") || sub.equals("sce_module")) continue;
                if (parent == null && !sub.equals("uroot")) continue;   // the super root's others are not listed
                dir.children.add(loadDir(subdirs.get(i), dir, sub, depth + 1));
            }
            return dir;
        }

        File find(String path) {
            Node n = uroot;
            for (String part : path.split("/")) {
                if (!(n instanceof Dir)) return null;
                Node next = null;
                for (Node c : ((Dir) n).children) {
                    if (c.name.equals(part)) next = c;
                }
                n = next;
                if (n == null) return null;
            }
            return n instanceof File ? (File) n : null;
        }

        private int readInt(long pos) throws IOException {
            byte[] b = new byte[4];
            reader.read(pos, b, 0, 4);
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        /** The file's bytes. */
        Source open(File file) throws IOException {
            Ino ino = inodes[file.ino];
            if (ino.blocks > 1 && ino.db[1] != -1) {
                if (!signed) throw new FormatException("unsigned PFS images probably shouldn't have noncontiguous blocks");
                if (ino.blocks > (1 << 24)) throw new FormatException("file has too many blocks");
                int[] blocks = new int[(int) ino.blocks];
                long remaining = ino.blocks;
                long sigsPerBlock = blockSize / 36;
                for (int i = 0; i < 12 && i < remaining; ++i) blocks[i] = ino.db[i];
                remaining -= 12;
                long offset = 12;
                for (int i = 0; i < remaining && i < sigsPerBlock; ++i) {
                    blocks[(int) (i + offset)] = readInt((long) ino.ib[0] * blockSize + i * 36L + 32);
                }
                remaining -= sigsPerBlock;
                offset += sigsPerBlock;
                for (long j = 0; j * sigsPerBlock < remaining; ++j) {
                    int indirect = readInt((long) ino.ib[1] * blockSize + j * 36L + 32);
                    for (int i = 0; i < sigsPerBlock && i + j * sigsPerBlock < remaining; ++i) {
                        blocks[(int) (i + offset)] = readInt((long) indirect * blockSize + i * 36L + 32);
                    }
                    offset += sigsPerBlock;
                }
                boolean contiguous = true;
                for (int i = 1; i < blocks.length; ++i) {
                    if (blocks[i - 1] + 1 != blocks[i]) {
                        contiguous = false;
                        break;
                    }
                }
                long span = (long) blocks.length * blockSize;
                if (file.size > span) throw new FormatException("file is larger than its blocks");
                if (!contiguous) return new BlockMapSource(reader, blockSize, blocks, file.size);
                return new OffsetSource(reader, (long) blocks[0] * blockSize, file.size);
            }
            if (ino.db[0] < 1) throw new FormatException("file has no data blocks");
            return new OffsetSource(reader, (long) ino.db[0] * blockSize, file.size);
        }
    }
}
