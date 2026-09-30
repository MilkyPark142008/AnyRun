package com.zcshou.gogogo;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;
import com.zcshou.utils.GoUtils;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    /** 崩溃日志单独再存一份，避免 xlog 的缓冲导致崩溃信息丢失 */
    public static final String CRASH_FILE_NAME = APP_NAME + ".crash.log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        initCrashHandler();

        initXlog();

        initOsmdroid();
    }

    /**
     * 注册全局未捕获异常处理器。
     *
     * <p>之前闪退时日志里没有任何堆栈：xlog 只记录主动打印的内容，进程被系统杀掉时
     * 什么线索都不会留下，所以这里把崩溃堆栈直接写进日志文件。</p>
     */
    private void initCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashInfo(thread, throwable);
            } catch (Throwable ignored) {
                // 记录崩溃日志本身不允许再抛异常
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private void writeCrashInfo(@NonNull Thread thread, @NonNull Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n==================== CRASH ====================\n");
        builder.append("time     : ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date()))
                .append('\n');
        builder.append("thread   : ").append(thread.getName()).append('\n');
        builder.append("device   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        try {
            builder.append("targetSdk: ").append(getApplicationInfo().targetSdkVersion).append('\n');
        } catch (Throwable ignored) {
        }
        try {
            builder.append("version  : ").append(GoUtils.getVersionName(this)).append('\n');
        } catch (Throwable ignored) {
        }
        builder.append(Log.getStackTraceString(throwable));
        builder.append("===============================================\n");

        // 1) 交给 xlog：保证“问题反馈”里分享的 GoGoGo.log 也包含崩溃信息
        try {
            XLog.e("CRASH: uncaught exception", throwable);
        } catch (Throwable ignored) {
        }

        // 2) 直接落盘并 flush：即使进程马上被杀也不会丢
        File logPath = getExternalFilesDir("Logs");
        if (logPath == null) {
            return;
        }

        appendCrashLog(new File(logPath, LOG_FILE_NAME), builder.toString());
        appendCrashLog(new File(logPath, CRASH_FILE_NAME), builder.toString());
    }

    private void appendCrashLog(File file, String content) {
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(content);
            writer.flush();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 初始化 osmdroid（瓦片地图引擎）
     */
    private void initOsmdroid() {
        IConfigurationProvider osmConfig = Configuration.getInstance();
        // 读取 osmdroid 自身的配置项（瓦片缓存大小、过期时间等）
        osmConfig.load(this, PreferenceManager.getDefaultSharedPreferences(this));
        // OSM / Esri 的瓦片服务器要求带上有意义的 User-Agent，否则会直接返回 403
        osmConfig.setUserAgentValue(getPackageName());
        // 瓦片缓存放到应用私有目录，避免申请存储权限
        File basePath = new File(getCacheDir(), "osmdroid");
        osmConfig.setOsmdroidBasePath(basePath);
        osmConfig.setOsmdroidTileCache(new File(basePath, "tiles"));
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        File logPath = getExternalFilesDir("Logs");
        if (logPath != null) {
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new NeverBackupStrategy())              // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // 指定日志文件清除策略，默认为 NeverCleanStrategy()
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}
