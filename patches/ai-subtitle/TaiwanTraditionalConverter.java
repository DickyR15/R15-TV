package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Taiwan Traditional Chinese conversion powered by official OpenCC 1.4.2.
 * Uses s2twp only; all translated captions pass through this class before display.
 */
public final class TaiwanTraditionalConverter implements AutoCloseable {
    private static final String ASSET_ROOT = "opencc-1.4.2";
    private static final String DATA_DIR = "opencc-1.4.2";
    private static final AtomicReference<TaiwanTraditionalConverter> INSTANCE = new AtomicReference<>();

    private long handle;

    public static synchronized TaiwanTraditionalConverter getInstance(Context context) throws IOException {
        TaiwanTraditionalConverter current = INSTANCE.get();
        if (current != null && current.handle != 0) return current;
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), DATA_DIR);
        File config = new File(root, "config/s2twp.json");
        File dictionaries = new File(root, "dictionary");
        if (!isComplete(config, dictionaries)) {
            deleteRecursively(root);
            copyAssetsRecursively(app.getAssets(), ASSET_ROOT, root);
        }
        if (!isComplete(config, dictionaries)) {
            throw new IOException("OpenCC 1.4.2 臺灣繁體字典不完整，請重新安裝／修復資產");
        }
        TaiwanTraditionalConverter created = new TaiwanTraditionalConverter(
                config.getAbsolutePath(), dictionaries.getAbsolutePath());
        INSTANCE.set(created);
        return created;
    }

    private TaiwanTraditionalConverter(String configPath, String dictionaryPath) {
        handle = nativeCreate(configPath, dictionaryPath);
        if (handle == 0) throw new IllegalStateException("無法載入 OpenCC 1.4.2");
    }

    public synchronized String convert(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (handle == 0) throw new IllegalStateException("OpenCC 1.4.2 尚未初始化");
        String converted = nativeConvert(handle, text);
        return converted == null || converted.trim().isEmpty() ? text : converted.trim();
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
        INSTANCE.compareAndSet(this, null);
    }

    private static boolean isComplete(File config, File dictionaries) {
        if (!config.isFile() || !dictionaries.isDirectory()) return false;
        String[] required = {
                "CJK_Compatibility_Ideographs.ocd2",
                "STPhrases.ocd2",
                "STPhrases_GeneratedFromRegionalPhrases.ocd2",
                "STCharacters.ocd2",
                "TWPhrases.ocd2",
                "TWVariantsPhrases.ocd2",
                "TWVariants.ocd2"
        };
        for (String name : required) {
            File file = new File(dictionaries, name);
            if (!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("無法清除舊版 OpenCC 資產：" + file.getName());
    }

    private static void copyAssetsRecursively(AssetManager assets, String assetPath, File output) throws IOException {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!output.exists() && !output.mkdirs()) throw new IOException("無法建立 OpenCC 資料夾：" + output);
            for (String child : children) {
                copyAssetsRecursively(assets, assetPath + "/" + child, new File(output, child));
            }
            return;
        }
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("無法建立 OpenCC 資料夾：" + parent);
        }
        File temporary = new File(output.getParentFile(), output.getName() + ".part");
        try (InputStream in = assets.open(assetPath); FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
            out.getFD().sync();
        } catch (Throwable error) {
            temporary.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("複製 OpenCC 資產失敗：" + assetPath, error);
        }
        if (output.exists() && !output.delete()) {
            temporary.delete();
            throw new IOException("無法更新 OpenCC 資產：" + output);
        }
        if (!temporary.renameTo(output)) {
            temporary.delete();
            throw new IOException("無法安裝 OpenCC 資產：" + output);
        }
    }

    private static native long nativeCreate(String configFile, String dictionaryDirectory);
    private static native String nativeConvert(long handle, String text);
    private static native void nativeDestroy(long handle);

    static {
        System.loadLibrary("r15opencc");
    }
}
