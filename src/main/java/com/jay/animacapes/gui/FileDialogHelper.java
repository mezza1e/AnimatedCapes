package com.jay.animacapes.gui;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FileDialogHelper {
    private static final ExecutorService DIALOG_THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnimaCapes-FileDialog");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean dialogOpen = false;

    private static final String CAPE_PICKER_BASE64 =
        "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";

    public static boolean isDialogOpen() {
        return dialogOpen;
    }

    private static File getOrCreatePickerExe() {
        try {
            File targetDir = new File(GalleryFolder.getFolder().getParentFile(), "tools");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File exe = new File(targetDir, "cape_picker.exe");
            if (!exe.exists() || exe.length() != 4608) {
                byte[] bytes = Base64.getDecoder().decode(CAPE_PICKER_BASE64);
                Files.write(exe.toPath(), bytes);
            }
            if (exe.exists() && exe.canExecute()) {
                return exe;
            }
        } catch (Throwable t) {
            System.err.println("[AnimaCapes] Failed to extract cape_picker.exe: " + t.getMessage());
        }
        return null;
    }

    private static File openWindowsProcessPicker() {
        File pickerExe = getOrCreatePickerExe();
        if (pickerExe != null && pickerExe.exists()) {
            try {
                String galleryPath = GalleryFolder.getFolder().getAbsolutePath();
                ProcessBuilder pb = new ProcessBuilder(pickerExe.getAbsolutePath(), galleryPath);
                pb.redirectErrorStream(false);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    boolean exited = process.waitFor(10, TimeUnit.MINUTES);
                    if (exited && line != null && !line.trim().isEmpty()) {
                        File picked = new File(line.trim());
                        if (picked.exists() && picked.isFile()) {
                            return picked;
                        }
                    }
                }
                return null;
            } catch (Throwable t) {
                System.err.println("[AnimaCapes] Process file picker failed, falling back to PowerShell: " + t.getMessage());
            }
        }

        // PowerShell fallback
        try {
            String psCmd = "[System.Reflection.Assembly]::LoadWithPartialName('System.windows.forms') | Out-Null; " +
                    "$d = New-Object System.Windows.Forms.OpenFileDialog; " +
                    "$d.Title = 'Select Cape File (*.webp, *.gif, *.png, *.jpg, *.jpeg)'; " +
                    "$d.Filter = 'Cape Images (*.webp;*.gif;*.png;*.jpg;*.jpeg)|*.webp;*.gif;*.png;*.jpg;*.jpeg|All Files (*.*)|*.*'; " +
                    "$d.InitialDirectory = '" + GalleryFolder.getFolder().getAbsolutePath().replace("'", "''") + "'; " +
                    "if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::WriteLine($d.FileName) }";

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psCmd);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor(10, TimeUnit.MINUTES);
                if (line != null && !line.trim().isEmpty()) {
                    File picked = new File(line.trim());
                    if (picked.exists() && picked.isFile()) {
                        return picked;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            System.err.println("[AnimaCapes] PowerShell picker failed: " + t.getMessage());
        }

        return null;
    }

    private static File openMacProcessPicker() {
        try {
            String script = "POSIX path of (choose file with prompt \"Select Cape File\" of type {\"webp\", \"gif\", \"png\", \"jpg\", \"jpeg\"})";
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor(10, TimeUnit.MINUTES);
                if (line != null && !line.trim().isEmpty()) {
                    File picked = new File(line.trim());
                    if (picked.exists() && picked.isFile()) {
                        return picked;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static File openLinuxProcessPicker() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "zenity", "--file-selection",
                "--title=Select Cape File",
                "--file-filter=Cape Images (*.webp *.gif *.png *.jpg *.jpeg) | *.webp *.gif *.png *.jpg *.jpeg",
                "--file-filter=All Files | *"
            );
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor(10, TimeUnit.MINUTES);
                if (line != null && !line.trim().isEmpty()) {
                    File picked = new File(line.trim());
                    if (picked.exists() && picked.isFile()) {
                        return picked;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static File openImageFileChooser() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            File picked = openWindowsProcessPicker();
            if (picked != null) return picked;
        } else if (os.contains("mac")) {
            File picked = openMacProcessPicker();
            if (picked != null) return picked;
        } else if (os.contains("nix") || os.contains("nux")) {
            File picked = openLinuxProcessPicker();
            if (picked != null) return picked;
        }

        // Ultimate fallback to TinyFileDialogs if out-of-process picker returned null due to platform lack
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.webp"));
            filters.put(stack.UTF8("*.gif"));
            filters.put(stack.UTF8("*.png"));
            filters.put(stack.UTF8("*.jpg"));
            filters.put(stack.UTF8("*.jpeg"));
            filters.flip();

            String result = TinyFileDialogs.tinyfd_openFileDialog(
                "Select Cape File (*.webp, *.gif, *.png, *.jpg, *.jpeg)",
                GalleryFolder.getFolder().getAbsolutePath(),
                filters,
                "Cape Images (*.webp, *.gif, *.png, *.jpg, *.jpeg)",
                false
            );

            if (result != null && !result.trim().isEmpty()) {
                File f = new File(result);
                if (f.exists() && f.isFile()) {
                    return f;
                }
            }
        } catch (Throwable t) {
            System.err.println("[AnimaCapes] Native file dialog fallback error: " + t.getMessage());
        }
        return null;
    }

    private static void executeOnMainThread(Runnable task) {
        if (task == null) return;
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Method getInstanceMethod = mcClass.getMethod("getInstance");
            Object mc = getInstanceMethod.invoke(null);
            if (mc != null) {
                Method executeMethod = mcClass.getMethod("execute", Runnable.class);
                executeMethod.invoke(mc, task);
                return;
            }
        } catch (Throwable t1) {
            try {
                Class<?> mcClass = Class.forName("net.minecraft.class_310");
                Method getInstanceMethod = mcClass.getMethod("method_1551");
                Object mc = getInstanceMethod.invoke(null);
                if (mc != null) {
                    Method executeMethod = mcClass.getMethod("execute", Runnable.class);
                    executeMethod.invoke(mc, task);
                    return;
                }
            } catch (Throwable ignored) {}
        }
        task.run();
    }

    public static void openImageFileChooserAsync(Consumer<File> onResult) {
        if (dialogOpen) {
            return;
        }
        dialogOpen = true;
        DIALOG_THREAD.submit(() -> {
            try {
                File chosen = openImageFileChooser();
                executeOnMainThread(() -> {
                    if (onResult != null) {
                        onResult.accept(chosen);
                    }
                });
            } catch (Throwable t) {
                System.err.println("[AnimaCapes] Error in async file dialog: " + t.getMessage());
                executeOnMainThread(() -> {
                    if (onResult != null) {
                        onResult.accept(null);
                    }
                });
            } finally {
                dialogOpen = false;
            }
        });
    }
}
