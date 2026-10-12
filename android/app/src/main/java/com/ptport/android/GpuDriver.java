package com.ptport.android;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * The custom Vulkan driver (docs/android.md). Adrenotools only loads drivers from the app's private storage, so the package's .so files
 * and meta.json go to files/gpu_driver together with main.txt, the file name of the main library; the game reads them when it starts
 * (OpenCustomGpuDriver in src/engine/platform/android_platform.cpp). Used by the main menu's Settings.
 */
final class GpuDriver {
    private GpuDriver() {
    }

    /**
     * Custom drivers (Turnip) only exist for Adreno, Qualcomm's GPU. The menu runs before the game has made a Vulkan device to ask, so this
     * goes by the SoC.
     */
    static boolean supported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "Qualcomm".equalsIgnoreCase(Build.SOC_MANUFACTURER)) {
            return true;
        }
        return Build.HARDWARE != null && Build.HARDWARE.toLowerCase(Locale.ROOT).contains("qcom");
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "gpu_driver");
    }

    static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String read(File file) throws java.io.IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The installed driver's name (meta.json's "name", else its library file name); null when none is installed. */
    static String name(Context context) {
        File dir = dir(context);
        String library;
        try {
            library = read(new File(dir, "main.txt")).trim();
        } catch (Exception e) {
            return null;
        }
        try {
            return new JSONObject(read(new File(dir, "meta.json"))).optString("name", library);
        } catch (Exception e) {
            return library;
        }
    }

    /** Unpacks the picked package over the installed driver. Returns null on success, else the reason. */
    static String install(Context context, Uri uri) {
        File dir = dir(context);
        File staging = new File(context.getFilesDir(), "gpu_driver.new");
        try {
            deleteRecursively(staging);
            staging.mkdirs();
            String[] libraries;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                libraries = DriverZip.extract(in, staging);
            }
            if (libraries.length == 0) {
                return "That is not a driver: the .zip has no .so file.";
            }
            String libraryName = "";
            try {
                libraryName = new JSONObject(read(new File(staging, "meta.json"))).optString("libraryName", "");
            } catch (Exception ignored) {
                // no meta.json, or not readable: the library is picked by its name
            }
            try (FileOutputStream out = new FileOutputStream(new File(staging, "main.txt"))) {
                out.write((DriverZip.mainLibrary(libraries, libraryName) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            deleteRecursively(dir);
            if (!staging.renameTo(dir)) {
                return "Could not put the driver in the app's storage.";
            }
            return null;
        } catch (Exception e) {
            return "Could not read that file: " + e.getMessage();
        } finally {
            deleteRecursively(staging);
        }
    }
}
