package com.ptport.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a GPU driver package .zip (meta.json and one or more .so files, as Winlator and the Adreno Tools driver
 * repositories distribute them). Nothing here depends on Android.
 */
final class DriverZip {
    /** Zip entries read, of any kind: a driver package holds a handful. */
    static final int MAX_ENTRIES = 256;

    /** Bytes written in all: a Turnip build is a few MB, a vendor driver tens. */
    static final long MAX_BYTES = 256L << 20;

    private DriverZip() {}

    /**
     * Extracts the .so files and meta.json of the zip into destDir (which must exist), flattening away any folder they
     * sit in; any other entry is skipped, and when two entries flatten to the same name the first one wins. Returns the
     * .so file names extracted, in zip order (empty if none).
     *
     * @throws IOException if the zip is unreadable or exceeds MAX_ENTRIES or MAX_BYTES
     */
    static String[] extract(InputStream in, File destDir) throws IOException {
        ArrayList<String> libraries = new ArrayList<>();
        ArrayList<String> written = new ArrayList<>();
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("too many entries in the zip");
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                boolean library = name.endsWith(".so");
                if (!library && !name.equals("meta.json")) continue;
                if (written.contains(name)) continue;
                written.add(name);
                try (FileOutputStream out = new FileOutputStream(new File(destDir, name))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_BYTES) throw new IOException("the zip unpacks to too much data");
                        out.write(buffer, 0, read);
                    }
                }
                if (library) libraries.add(name);
            }
        }
        return libraries.toArray(new String[0]);
    }

    /**
     * The main driver among the extracted libraries: the one meta.json's "libraryName" names; failing that the only
     * one, or among several the first whose name holds "vulkan"; failing that the first.
     */
    static String mainLibrary(String[] libraries, String libraryName) {
        for (String name : libraries) {
            if (name.equals(libraryName)) return name;
        }
        if (libraries.length > 1) {
            for (String name : libraries) {
                if (name.contains("vulkan")) return name;
            }
        }
        return libraries[0];
    }
}
