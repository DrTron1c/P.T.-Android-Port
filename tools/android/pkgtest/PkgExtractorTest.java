package com.ptport.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Desktop test of PkgExtractor (no Android classes in it): run by run.sh with the packages that make_test_pkgs.py builds.
 *   java -cp <classes> com.ptport.android.PkgExtractorTest <package dir>
 */
public final class PkgExtractorTest {
    private static int failures;
    private static int checks;

    private static void check(boolean ok, String what) {
        ++checks;
        if (!ok) {
            ++failures;
            System.out.println("FAIL: " + what);
        }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; ++i) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[1 << 20];
        try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static PkgExtractor.Source fileSource(final RandomAccessFile file) throws IOException {
        final long length = file.length();
        return new PkgExtractor.Source() {
            @Override
            public long length() {
                return length;
            }

            @Override
            public void read(long pos, byte[] buf, int off, int len) throws IOException {
                file.seek(pos);
                file.readFully(buf, off, len);
            }
        };
    }

    /** IEEE 1619 AES-XTS vectors 1 and 2 (data unit of 32 bytes): the decryption must give the plaintext back. */
    private static void xtsVectors() throws Exception {
        PkgExtractor.Xts a = new PkgExtractor.Xts(new byte[16], new byte[16]);
        byte[] c1 = hex("917cf69ebd68b2ec9b9fe9a3eadda692cd43d2f59598ed858c02c2652fbf922e");
        a.decrypt(c1, 0, 1, 32, 0);
        check(Arrays.equals(c1, new byte[32]), "XTS vector 1");

        byte[] key1 = new byte[16];
        byte[] key2 = new byte[16];
        Arrays.fill(key1, (byte) 0x11);
        Arrays.fill(key2, (byte) 0x22);
        PkgExtractor.Xts b = new PkgExtractor.Xts(key1, key2);
        byte[] c2 = hex("c454185e6a16936e39334038acef838bfb186fff7480adc4289382ecd6d394f0");
        byte[] expect = new byte[32];
        Arrays.fill(expect, (byte) 0x44);
        b.decrypt(c2, 0, 1, 32, 0x3333333333L);
        check(Arrays.equals(c2, expect), "XTS vector 2");
    }

    private static void keyDerivation() throws Exception {
        // the same value make_test_pkgs.py computes, checked against its package digests below; here only that it is stable
        byte[] a = PkgExtractor.computeKeys("UP9000-CUSA01127_00-PTPTPTPTPTPTPT01", "00000000000000000000000000000000", 1);
        byte[] b = PkgExtractor.computeKeys("UP9000-CUSA01127_00-PTPTPTPTPTPTPT01", "00000000000000000000000000000000", 1);
        byte[] c = PkgExtractor.computeKeys("UP9000-CUSA01127_00-PTPTPTPTPTPTPT01", "00000000000000000000000000000000", 3);
        check(a.length == 32 && Arrays.equals(a, b) && !Arrays.equals(a, c), "key derivation is stable and index dependent");
    }

    public static void main(String[] args) throws Exception {
        xtsVectors();
        keyDerivation();

        File dir = new File(args[0]);
        List<String> lines = Files.readAllLines(new File(dir, "manifest.txt").toPath());
        Path work = Files.createTempDirectory("pkgtest");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split("\\|", -1);
            String name = f[0];
            File pkgFile = new File(dir, f[1]);
            boolean expectOk = f[2].equals("ok");
            File out = new File(work.toFile(), name);
            final long[] last = { -1 };
            final boolean[] monotonic = { true };
            long t0 = System.currentTimeMillis();
            try (RandomAccessFile raf = new RandomAccessFile(pkgFile, "r")) {
                PkgExtractor.Result r = null;
                String error = null;
                try {
                    r = PkgExtractor.extract(fileSource(raf), out, new PkgExtractor.Progress() {
                        @Override
                        public void status(String message) {
                        }

                        @Override
                        public void bytes(long done, long total) {
                            if (done < last[0]) monotonic[0] = false;
                            last[0] = done;
                        }
                    });
                } catch (IOException e) {
                    error = e.getMessage();
                }
                if (expectOk) {
                    check(error == null, name + ": extracts (" + error + ")");
                    if (error == null) {
                        for (String item : f[3].split(";")) {
                            String[] kv = item.split("=");
                            File got = new File(out, kv[0]);
                            check(got.isFile() && sha256(got).equals(kv[1]), name + ": " + kv[0] + " is byte for byte the original");
                        }
                        check(!new File(out, "pathid_list_ps4.bin").exists() == !f[3].contains("pathid_list_ps4.bin"), name + ": path list present as expected");
                        check(new File(out, "source.txt").isFile(), name + ": source.txt written");
                        check(monotonic[0], name + ": progress never goes back");
                        if (!f[4].equals("-")) {
                            boolean found = false;
                            for (String w : r.warnings) if (w.contains(f[4])) found = true;
                            check(found, name + ": warning mentions '" + f[4] + "' (" + r.warnings + ")");
                        }
                        String[] leftovers = out.list();
                        boolean part = false;
                        for (String s : leftovers) if (s.endsWith(".part")) part = true;
                        check(!part, name + ": no .part file left");
                    }
                } else {
                    check(error != null && error.contains(f[3]), name + ": refused with '" + f[3] + "' (got: " + error + ")");
                    check(!new File(out, "chunk1.psarc").exists(), name + ": nothing written when refused");
                }
            }
            System.out.println(String.format("%-16s %s  %d ms", name, expectOk ? "ok     " : "refused", System.currentTimeMillis() - t0));
        }
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
