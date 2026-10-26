package com.fongmi.android.tv.db;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BackupManager {

    private static final String TAG = BackupManager.class.getSimpleName();
    private static final int MAX_BACKUPS = 7;
    private static final String BACKUP_EXTENSION = ".tv";
    private static final String LEGACY_BACKUP_PREFIX = "tv-";
    private static final String LEGACY_BACKUP_SUFFIX = ".bk.gz";

    public static void backup() {
        backup(new Callback());
    }

    public static void backup(Callback callback) {
        Task.executeSerial(() -> {
            boolean success = save();
            post(callback, success);
            if (success) trim();
        });
    }

    public static void restore(File file, Callback callback) {
        Task.executeSerial(() -> post(callback, restore(file)));
    }

    public static List<File> getFiles() {
        migrate();
        List<File> backups = new ArrayList<>(Path.list(Path.backup()));
        backups.removeIf(file -> !file.isFile() || !file.getName().endsWith(BACKUP_EXTENSION));
        for (File file : Path.list(Path.tv())) if (isUnmigratedLegacy(file)) backups.add(file);
        backups.sort(Comparator.comparingLong(File::lastModified).reversed());
        return backups;
    }

    private static boolean save() {
        Backup backup = Backup.create();
        File file = new File(Path.backup(), LocalDate.now().format(Formatters.DATE) + BACKUP_EXTENSION);
        return !backup.getConfig().isEmpty() && FileUtil.gzipCompress(backup.toString().getBytes(StandardCharsets.UTF_8), file);
    }

    private static boolean restore(File file) {
        Backup backup = Backup.objectFrom(FileUtil.readGzip(file));
        boolean valid = !backup.getConfig().isEmpty();
        if (valid) backup.restore();
        return valid;
    }

    private static void post(Callback callback, boolean success) {
        App.post(success ? callback::success : callback::error);
    }

    private static void trim() {
        getFiles().stream().skip(MAX_BACKUPS).forEach(Path::clear);
    }

    private static void migrate() {
        // 旧版本把 .tv 备份写在公共外部存储（TV 盒子上常因权限不足而备份失败），
        // 统一搬进应用专属目录，搬过的不再重复处理。
        for (File file : Path.list(Path.backupLegacy())) if (isLegacyTv(file)) migrate(file);
        for (File file : Path.list(Path.tv())) if (isLegacy(file)) migrate(file);
    }

    /** 旧位置里的 .tv 备份文件。 */
    private static boolean isLegacyTv(File file) {
        return file.isFile() && file.getName().endsWith(BACKUP_EXTENSION);
    }

    private static boolean isLegacy(File file) {
        String name = file.getName();
        return file.isFile() && name.startsWith(LEGACY_BACKUP_PREFIX) && name.endsWith(LEGACY_BACKUP_SUFFIX);
    }

    private static void migrate(File file) {
        File target = getMigrationTarget(file);
        if (target.exists()) return;
        try {
            Path.move(file, target);
        } catch (IOException e) {
            Log.w(TAG, "Unable to migrate backup file=" + file, e);
        }
    }

    private static boolean isUnmigratedLegacy(File file) {
        return isLegacy(file) && !getMigrationTarget(file).exists();
    }

    private static File getMigrationTarget(File file) {
        String name = file.getName();
        // 旧公共目录里的 .tv 备份：文件名不变，直接搬进新目录
        if (name.endsWith(BACKUP_EXTENSION)) return new File(Path.backup(), name);
        String date = name.substring(LEGACY_BACKUP_PREFIX.length(), name.length() - LEGACY_BACKUP_SUFFIX.length());
        return new File(Path.backup(), date + BACKUP_EXTENSION);
    }
}
