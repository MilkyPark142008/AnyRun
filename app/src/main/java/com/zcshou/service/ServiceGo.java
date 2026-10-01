package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;
import com.zcshou.script.ScriptParser;
import com.zcshou.script.ScriptPlayer;
import com.zcshou.script.ScriptRoute;
import com.zcshou.script.ScriptStore;
import com.zcshou.script.ScriptWaypoint;
import com.zcshou.utils.GoUtils;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    /* 下面几个位置字段会同时被主线程（绑定调用 / 摇杆）和定位线程（脚本推进）读写，统一加 volatile */
    private volatile double mCurLat = DEFAULT_LAT;
    private volatile double mCurLng = DEFAULT_LNG;
    private volatile double mCurAlt = DEFAULT_ALT;
    private volatile float mCurBea = DEFAULT_BEA;
    private volatile double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    /* 服务被系统重新拉起（intent 为 null）时，用这里保存的上次位置恢复，避免拿不到位置 */
    private static final String KEY_LAST_LNG = "service_last_lng";
    private static final String KEY_LAST_LAT = "service_last_lat";
    private static final String KEY_LAST_ALT = "service_last_alt";
    /** 服务被系统重新拉起时，用来恢复正在播放的脚本 */
    private static final String KEY_LAST_SCRIPT_ID = "service_last_script_id";
    /* 脚本播放状态 */
    public static final int SCRIPT_STATE_IDLE = 0;
    public static final int SCRIPT_STATE_PLAYING = 1;
    public static final int SCRIPT_STATE_FINISHED = 2;
    private volatile int mScriptState = SCRIPT_STATE_IDLE;
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;
    // 脚本相关
    private ScriptPlayer mScriptPlayer;
    private ScriptRoute mScriptRoute;
    private ScriptStore mScriptStore;
    private ScriptListener mScriptListener;

    /* 点击移动：主界面左下角开关控制，点地图朝目标点按“行走方式”持续走 */
    /** true = 点击移动生效（false = 摇杆移动）；开启时摇杆方向输入暂停 */
    private volatile boolean mClickMoveEnabled;
    /** 有一个未到达的行走目标 */
    private volatile boolean mHasClickTarget;
    private volatile double mClickTargetLng;
    private volatile double mClickTargetLat;
    /** 上一次朝目标推进的时刻（毫秒），按真实间隔步进 */
    private long mLastClickStepMs;
    /** 到达目标的提示要发到主线程 */
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** 脚本播放状态回调，供界面展示“第几个点 / 当前状态” */
    public interface ScriptListener {
        void onScriptSegment(ScriptRoute route, int index, ScriptWaypoint.Mode mode, double speed);

        void onScriptFinish(ScriptRoute route);

        void onScriptStopped();
    }

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        XLog.i("SERVICEGO: onCreate");

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        mScriptStore = new ScriptStore(this);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        double lng = DEFAULT_LNG;
        double lat = DEFAULT_LAT;
        double alt = DEFAULT_ALT;
        String scriptId = null;

        if (intent != null) {
            lng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            lat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            alt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
            scriptId = intent.getStringExtra(MainActivity.SCRIPT_ROUTE_ID);
        } else {
            // 服务被系统重新拉起时 intent 为 null（原实现在这里会直接 NPE 闪退），
            // 这里改用上次保存的位置，保证服务能正常恢复
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            lng = parseDouble(preferences.getString(KEY_LAST_LNG, null), DEFAULT_LNG);
            lat = parseDouble(preferences.getString(KEY_LAST_LAT, null), DEFAULT_LAT);
            alt = parseDouble(preferences.getString(KEY_LAST_ALT, null), DEFAULT_ALT);
            scriptId = preferences.getString(KEY_LAST_SCRIPT_ID, null);
            XLog.i("SERVICEGO: restart with null intent");
        }

        mCurLng = lng;
        mCurLat = lat;
        mCurAlt = alt;
        saveLastPosition();

        if (mJoyStick != null) {
            try {
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - setCurrentPosition", e);
            }
        }

        // 脚本模式：按脚本预设自动移动；没有脚本 id 时保持原来的“定点点位”行为
        if (!isBlank(scriptId)) {
            startScript(scriptId);
        } else if (intent != null) {
            // 手动传送（带坐标、不带脚本 id）会打断正在播放的脚本：
            // 否则脚本线程会继续覆盖位置，界面却提示“已传送到新位置”，两边打架
            if (mScriptPlayer != null && mScriptPlayer.isPlaying()) {
                XLog.i("SERVICEGO: manual teleport stops running script");
                stopScript();
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isStop = true;

        if (mScriptPlayer != null) {
            mScriptPlayer.stop();
            mScriptPlayer = null;
        }
        mScriptRoute = null;
        mScriptState = SCRIPT_STATE_IDLE;
        mScriptListener = null;

        // 服务销毁 = 脚本播放彻底结束，把“正在播放”的持久状态一并清掉。
        // 之前只在 stopScript() 里清理，而“没绑定上服务时点停止”走的是 stopService() 路径，
        // 存储里的运行 id 会残留：下次 onResume 读到过期状态，界面把“开始移动”显示成
        // “停止移动”，点开始又走成了停止，表现为“停止后再开始不移动”。
        // 系统杀进程（SIGKILL）不会走到 onDestroy，服务重启恢复脚本的逻辑不受影响。
        try {
            if (mScriptStore != null) {
                mScriptStore.setRunningScriptId(null);
            }
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .remove(KEY_LAST_SCRIPT_ID)
                    .apply();
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - clear script state on destroy");
        }

        try {
            if (mLocHandler != null) {
                mLocHandler.removeMessages(HANDLER_MSG_ID);
            }
            if (mLocHandlerThread != null) {
                mLocHandlerThread.quit();
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - stop location thread", e);
        }
        mLocHandler = null;
        mLocHandlerThread = null;

        if (mJoyStick != null) {
            try {
                mJoyStick.destroy();
            } catch (Throwable e) {
                XLog.e("SERVICEGO: ERROR - destroy joystick", e);
            }
            mJoyStick = null;
        }

        removeTestProviderNetwork();
        removeTestProviderGPS();

        if (mActReceiver != null) {
            try {
                unregisterReceiver(mActReceiver);
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - unregisterReceiver", e);
            }
            mActReceiver = null;
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - stopForeground", e);
        }

        super.onDestroy();
    }

    private void initNotification() {
        try {
            mActReceiver = new NoteActionReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
            filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
            registerReceiver(mActReceiver, filter);

            // 渠道名会原样显示在系统“通知设置”里，必须是给用户看的文字
            String channelName = getResources().getString(R.string.app_note_channel_name);
            NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(mChannel);
            }

            //准备intent
            Intent clickIntent = new Intent(this, MainActivity.class);
            PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
            Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
            PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
            Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
            PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                    .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                    .setContentTitle(getResources().getString(R.string.app_name))
                    .setContentText(getResources().getString(R.string.app_service_tips))
                    .setContentIntent(clickPI)
                    .addAction(new NotificationCompat.Action(R.drawable.ic_position, getResources().getString(R.string.note_show), showPendingPI))
                    .addAction(new NotificationCompat.Action(R.drawable.ic_fly, getResources().getString(R.string.note_hide), hidePendingPI))
                    // 通知小图标不能再用 R.mipmap.ic_launcher：自适应图标在通知栏只取前景层，
                    // 而新图标的前景是透明的（整张图在背景层），会显示成空白。
                    // 这里直接用图钉矢量，和原版通知图标的轮廓保持一致。
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();

            startForeground(SERVICE_GO_NOTE_ID, notification);
        } catch (Throwable e) {
            // 前台服务起不来时，系统会在 5 秒后直接杀掉进程，这里主动结束服务更安全
            XLog.e("SERVICEGO: ERROR - initNotification", e);
            stopSelf();
        }
    }

    private void initJoyStick() {
        try {
            mJoyStick = new JoyStick(this);
            mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
                @Override
                public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                    // 位置的两种接管者：脚本播放（否则位移不到 100ms 就被覆盖）、
                    // 或点击移动开启中（点击与摇杆是切换关系，此时摇杆方向输入暂停）
                    if (isScriptDrivingPosition() || mClickMoveEnabled) {
                        return;
                    }

                    mSpeed = speed;
                    // 根据当前的经纬度和距离，计算下一个经纬度
                    // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                    // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                    // 具体见：http://wp.mlab.tw/?p=2200
                    mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                    mCurLat += disLat / 110.574;
                    mCurBea = (float) angle;
                }

                @Override
                public void onPositionInfo(double lng, double lat, double alt) {
                    // 脚本正在接管位置：忽略这次选点传送并给出提示。
                    // 以前会直接改位置——表现就是“位置被拉回选点处、脚本移动被打断”
                    if (isScriptDrivingPosition()) {
                        GoUtils.DisplayToast(ServiceGo.this,
                                getString(R.string.joystick_ignore_teleport));
                        return;
                    }

                    mCurLng = lng;
                    mCurLat = lat;
                    mCurAlt = alt;
                    // 与 binder.setPosition 的手动传送保持一致：速度、方向复位，
                    // 否则“跑完脚本（speed=0）再手动选点”会一直注入 speed=0，上层应用会判定为没在移动
                    mSpeed = 1.2;
                    mCurBea = DEFAULT_BEA;
                    // 传送到了新位置，进行中的点击移动目标作废
                    mHasClickTarget = false;
                    saveLastPosition();
                }
            });
            mJoyStick.show();
        } catch (Throwable e) {
            // 悬浮窗（摇杆）创建失败不应该让整个进程崩溃：模拟位置本身仍然可用
            mJoyStick = null;
            XLog.e("SERVICEGO: ERROR - initJoyStick", e);
        }
    }

    private void initGoLocation() {
        if (mLocManager == null) {
            XLog.e("SERVICEGO: ERROR - LocationManager is null");
            return;
        }

        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (isStop) {
                    return;
                }

                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        // 点击移动先按“行走方式”的速度朝目标走一步，再统一注入本轮位置
                        advanceClickTarget();
                        setLocationNetwork();
                        setLocationGPS();

                        // 脚本模式：定时推进位置（内部会按“走 / 跑 / 骑”分段速度前进）
                        if (mScriptPlayer != null && mScriptPlayer.isPlaying()) {
                            mScriptPlayer.onTick();
                        }
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage", e);
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    // 注入模拟位置失败不能让进程崩溃，下一轮继续重试
                    XLog.e("SERVICEGO: ERROR - handleMessage", e);
                }

                if (!isStop) {
                    sendEmptyMessage(HANDLER_MSG_ID);
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 注入用的朝向：统一到 0~360 并避开 0。
     *
     * <p>Location.setBearing(0) 会把“有朝向”标记清掉（hasBearing() 变成 false），
     * 地图定位图层就会改画小人而不是方向箭头；脚本到达终点段的 bearing(点,点)、
     * 服务默认朝向都会算出 0，这里统一挡掉，保证人物箭头始终显示。</p>
     */
    private float injectionBearing() {
        float bearing = (mCurBea + 360.0f) % 360.0f;
        return bearing == 0.0f ? 0.01f : bearing;
    }

    /*============================== 点击移动 ==============================*/

    /** 切换“点击移动 ⇄ 摇杆移动”（主界面左下角开关）；切换即作废进行中的行走目标 */
    private void setClickMoveMode(boolean enabled) {
        mClickMoveEnabled = enabled;
        mHasClickTarget = false;

        if (mJoyStick != null) {
            try {
                // 收起 / 恢复方向摇杆（点击与摇杆为切换关系，行走方式按钮保留）
                mJoyStick.setClickMoveEnabled(enabled);
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - setClickMoveEnabled", e);
            }
        }
    }

    /**
     * 点击移动：朝目标点按摇杆当前选择的行走方式（走 / 跑 / 骑的速度）持续前进。
     *
     * <p>脚本播放时位置由脚本接管，这里让路；到达目标后停止并提示一次。</p>
     */
    private void advanceClickTarget() {
        if (!mClickMoveEnabled || !mHasClickTarget) {
            return;
        }
        if (mScriptState == SCRIPT_STATE_PLAYING && mScriptPlayer != null && mScriptPlayer.isPlaying()) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = mLastClickStepMs;
        mLastClickStepMs = now;
        double dt = Math.min(1.0D, Math.max(0.0D, (now - last) / 1000.0D));
        if (dt <= 0) {
            return;
        }

        double speed = mJoyStick != null ? mJoyStick.getCurrentSpeed() : 1.2D;
        if (speed <= 0) {
            speed = 1.2D;
        }
        double step = speed * dt;

        double distance = ScriptPlayer.distanceMeters(mCurLng, mCurLat, mClickTargetLng, mClickTargetLat);
        float bearing = bearingBetween(mCurLng, mCurLat, mClickTargetLng, mClickTargetLat);
        mCurBea = bearing;
        mSpeed = speed;

        if (distance <= Math.max(step, 0.05D)) {
            // 到达：落点精确对齐目标
            mCurLng = mClickTargetLng;
            mCurLat = mClickTargetLat;
            mHasClickTarget = false;
            notifyClickArrived();
        } else {
            // 一步的位移很小，平面近似的误差可以忽略
            double ratio = step / distance;
            mCurLng += (mClickTargetLng - mCurLng) * ratio;
            mCurLat += (mClickTargetLat - mCurLat) * ratio;
        }

        if (mJoyStick != null) {
            try {
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            } catch (Exception e) {
                XLog.e("SERVICEGO: ERROR - click setCurrentPosition", e);
            }
        }
    }

    /** 两点间方位角（0~360，正北为 0；避开 0 以免注入端被判定“没有朝向”） */
    private static float bearingBetween(double lng1, double lat1, double lng2, double lat2) {
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);

        double y = Math.sin(dLng) * Math.cos(lat2r);
        double x = Math.cos(lat1r) * Math.sin(lat2r) - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLng);
        float bearing = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360.0D) % 360.0D);
        return bearing == 0.0f ? 0.01f : bearing;
    }

    /** 到达提示（Toast 必须在有 Looper 的线程，这里发到主线程） */
    private void notifyClickArrived() {
        mMainHandler.post(() -> GoUtils.DisplayToast(ServiceGo.this,
                getString(R.string.click_move_arrived)));
    }

    /*============================== 脚本模式 ==============================*/

    /** 创建一个绑定到本服务的脚本播放器（只创建一次） */
    private void initScriptPlayer() {
        mScriptPlayer = new ScriptPlayer();
        mScriptPlayer.setHandler(new ScriptPlayer.Handler() {
            @Override
            public void onScriptStart(ScriptRoute route, double lng, double lat, double alt) {
                XLog.i("SERVICEGO: script start - " + route.name);
            }

            @Override
            public synchronized void onScriptPosition(double lng, double lat, double alt, ScriptWaypoint.Mode mode,
                                         double speed, double bearing) {
                // 注意：这里运行在定位线程上，写的是 volatile 字段，随后由定位循环注入给系统
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
                mSpeed = speed;
                mCurBea = (float) bearing;

                if (mJoyStick != null) {
                    try {
                        mJoyStick.setCurrentPosition(lng, lat, alt);
                    } catch (Exception e) {
                        XLog.e("SERVICEGO: ERROR - script setCurrentPosition", e);
                    }
                }
            }

            @Override
            public synchronized void onScriptSegment(ScriptRoute route, int index, ScriptWaypoint.Mode mode, double speed) {
                XLog.i("SERVICEGO: script segment " + index + " - " + mode.key + " " + speed + "m/s");
                if (mScriptListener != null) {
                    mScriptListener.onScriptSegment(route, index, mode, speed);
                }
            }

            @Override
            public synchronized void onScriptFinish(ScriptRoute route) {
                mScriptState = SCRIPT_STATE_FINISHED;
                XLog.i("SERVICEGO: script finished - " + route.name);
                // 跑完 = 本次脚本到此结束：立刻清掉“正在播放”的持久状态与重启恢复标记。
                // 否则界面会一直按“播放中”处理——路线条显示“停止”、要先停一次才能再模拟、
                // 点击移动被拒、单点传送被要求确认，表现为“脚本结束后模拟位置不能立刻实现”
                try {
                    if (mScriptStore != null) {
                        mScriptStore.setRunningScriptId(null);
                    }
                    // 注意：这里在匿名 Handler 内部，this 是匿名对象不是 Service，必须写 ServiceGo.this
                    PreferenceManager.getDefaultSharedPreferences(ServiceGo.this).edit()
                            .remove(KEY_LAST_SCRIPT_ID)
                            .apply();
                } catch (Exception e) {
                    XLog.e("SERVICEGO: ERROR - clear script state on finish");
                }
                if (mScriptListener != null) {
                    mScriptListener.onScriptFinish(route);
                }
            }

            @Override
            public synchronized void onScriptError(String message) {
                mScriptState = SCRIPT_STATE_IDLE;
                XLog.e("SERVICEGO: script error - " + message);
                if (mScriptListener != null) {
                    mScriptListener.onScriptStopped();
                }
            }
        });
    }

    /** 读取设置里各状态的默认速度，顺序为 步行 / 跑步 / 骑行 */
    private double[] getModeSpeeds() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        double walk = parseDouble(preferences.getString("setting_walk", "1.2"), 1.2);
        double run = parseDouble(preferences.getString("setting_run", "3.6"), 3.6);
        double bike = parseDouble(preferences.getString("setting_bike", "10.0"), 10.0);

        return ScriptParser.modeSpeeds(walk, run, bike);
    }

    private double getSettingAltitude() {
        return parseDouble(
                PreferenceManager.getDefaultSharedPreferences(this).getString("setting_altitude", "55.0"), DEFAULT_ALT);
    }

    /**
     * 脚本此刻是否真正在接管位置：状态与播放器双确认。
     *
     * <p>只看 mScriptState 会被“状态残留”骗到（状态是 PLAYING、播放器其实早已停了），
     * 表现为摇杆怎么推都没反应；双确认后，残留状态既锁不住摇杆，也会在下次启动时被纠正。</p>
     */
    private boolean isScriptDrivingPosition() {
        return mScriptState == SCRIPT_STATE_PLAYING
                && mScriptPlayer != null
                && mScriptPlayer.isPlaying();
    }

    /**
     * 开始播放指定 id 的脚本。
     *
     * <p>位置对齐、状态切换、循环都由 {@link ScriptPlayer} 负责，这里只做加载与启动。</p>
     */
    private void startScript(String scriptId) {
        if (mScriptStore == null) {
            mScriptStore = new ScriptStore(this);
        }

        ScriptRoute route = mScriptStore.load(scriptId);
        if (route == null || route.points.isEmpty()) {
            XLog.e("SERVICEGO: script not found - " + scriptId);
            // 加载失败也把状态拉回 IDLE：否则“状态停在 PLAYING 但播放器没在跑”的残留
            // 会同时锁死摇杆（被当成播放中忽略）并让界面的兜底启动误判“已在播”而跳过
            mScriptState = SCRIPT_STATE_IDLE;
            if (mScriptListener != null) {
                mScriptListener.onScriptStopped();
            }
            return;
        }

        // 每次启动都按最新文本重新解析，保证编辑过的脚本立刻生效
        if (!isBlank(route.text)) {
            // 坐标系优先跟着脚本自己走；旧脚本没有记录时才回落到全局设置，
            // 避免用户改一次全局单选就让这条脚本整体偏移几百米
            boolean fromBd09 = route.fromBd09 != null
                    ? route.fromBd09
                    : PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(ScriptParser.KEY_SCRIPT_FROM_BD09, false);
            ScriptParser.ParseResult parseResult = ScriptParser.parse(route.text, fromBd09, getModeSpeeds());
            if (parseResult.isOk()) {
                route.points = parseResult.route.points;
                // 脚本里不写海拔时，用设置里的海拔补齐
                double alt = getSettingAltitude();
                for (ScriptWaypoint point : route.points) {
                    point.alt = alt;
                }
            } else {
                XLog.e("SERVICEGO: script parse failed - " + parseResult.error);
            }
        }

        if (mScriptPlayer == null) {
            initScriptPlayer();
        }

        mScriptRoute = route;
        mScriptState = SCRIPT_STATE_PLAYING;
        mScriptStore.setRunningScriptId(route.id);
        // 脚本开始接管位置：作废进行中的点击移动目标
        mHasClickTarget = false;

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(KEY_LAST_SCRIPT_ID, route.id)
                .apply();

        mScriptPlayer.start(route, mCurLng, mCurLat);

        // 脚本起点也记一份，服务重启后能回到轨迹附近
        saveLastPosition();
    }

    /** 停止脚本播放，回到手动控制（当前坐标保持不变） */
    public void stopScript() {
        if (mScriptPlayer != null) {
            mScriptPlayer.stop();
        }

        boolean hadScript = mScriptRoute != null || mScriptState != SCRIPT_STATE_IDLE;
        mScriptRoute = null;
        mScriptState = SCRIPT_STATE_IDLE;

        if (mScriptStore != null) {
            mScriptStore.setRunningScriptId(null);
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .remove(KEY_LAST_SCRIPT_ID)
                .apply();

        if (hadScript && mScriptListener != null) {
            mScriptListener.onScriptStopped();
        }
    }

    private void saveLastPosition() {
        try {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(KEY_LAST_LNG, Double.toString(mCurLng))
                    .putString(KEY_LAST_LAT, Double.toString(mCurLat))
                    .putString(KEY_LAST_ALT, Double.toString(mCurAlt))
                    .apply();
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - saveLastPosition", e);
        }
    }

    private void removeTestProviderGPS() {
        if (mLocManager == null) {
            return;
        }

        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Throwable e) {
            // 某些 ROM 在没有测试提供者时就会抛异常，属于预期内的情况
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        if (mLocManager == null) {
            return;
        }

        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Throwable e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        if (mLocManager == null) {
            return;
        }

        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(injectionBearing());           // 方向（度；避开 0，见 injectionBearing）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Throwable e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        if (mLocManager == null) {
            return;
        }

        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Throwable e) {
            // 某些 ROM 在没有测试提供者时就会抛异常，属于预期内的情况
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        if (mLocManager == null) {
            return;
        }

        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (Throwable e) {
            // 这里原来只捕获 SecurityException，一旦 ROM 抛出 IllegalArgumentException
            // 就会直接把整个进程带崩（点“模拟位置”后闪退的元凶之一），统一改为捕获 Throwable
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        if (mLocManager == null) {
            return;
        }

        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(injectionBearing());           // 方向（度；避开 0，见 injectionBearing）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Throwable e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    if (mJoyStick != null) {
                        mJoyStick.show();
                    }
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    if (mJoyStick != null) {
                        mJoyStick.hide();
                    }
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            if (mLocHandler == null) {
                XLog.e("SERVICEGO: ERROR - setPosition before handler ready");
                return;
            }

            // 手动传送会打断脚本播放，也会作废进行中的点击移动目标
            if (mScriptPlayer != null && mScriptPlayer.isPlaying()) {
                stopScript();
            }
            mHasClickTarget = false;

            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mSpeed = 1.2;
            mCurBea = DEFAULT_BEA;
            saveLastPosition();
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);

            if (mJoyStick != null) {
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            }
        }

        /** 开始播放脚本（重复调用会切换到新的脚本） */
        public boolean startScript(String scriptId) {
            if (isBlank(scriptId)) {
                return false;
            }

            ServiceGo.this.startScript(scriptId);
            return mScriptState == SCRIPT_STATE_PLAYING;
        }

        /** 停止脚本播放 */
        public void stopScript() {
            ServiceGo.this.stopScript();
        }

        /** 切换“点击移动 ⇄ 摇杆移动”（主界面左下角开关） */
        public void setClickMoveMode(boolean enabled) {
            ServiceGo.this.setClickMoveMode(enabled);
        }

        /**
         * 点击移动：朝目标点按摇杆当前选择的行走方式持续前进。
         *
         * @param lng 目标经度（WGS-84）
         * @param lat 目标纬度（WGS-84）
         * @return 未开启点击移动或脚本正在播放时返回 false
         */
        public boolean setClickTarget(double lng, double lat) {
            if (!mClickMoveEnabled || mScriptState == SCRIPT_STATE_PLAYING) {
                return false;
            }

            mClickTargetLng = lng;
            mClickTargetLat = lat;
            mLastClickStepMs = System.currentTimeMillis();
            mHasClickTarget = true;
            return true;
        }

        /** 作废进行中的行走目标 */
        public void cancelClickTarget() {
            mHasClickTarget = false;
        }

        /**
         * 播放过程中切换移动状态（走 / 跑 / 骑），立即对剩余路段生效。
         *
         * @param mode 新的移动状态
         * @return 是否切换成功（没在播放脚本时返回 false）
         */
        public boolean switchLiveMode(ScriptWaypoint.Mode mode) {
            if (mScriptPlayer == null || !mScriptPlayer.isPlaying() || mode == null) {
                return false;
            }

            double[] speeds = getModeSpeeds();
            double speed = speeds[Math.max(0, Math.min(speeds.length - 1, mode.ordinal()))];
            return mScriptPlayer.setLiveMode(mode, speed);
        }

        /** 当前播放中手动切换过的状态，没有切换过或没在播放时返回 null */
        public ScriptWaypoint.Mode getLiveMode() {
            return mScriptPlayer == null ? null : mScriptPlayer.getLiveMode();
        }

        /** 当前正在生效的移动状态（手动切换过返回切换值，否则是脚本当前段的状态） */
        public ScriptWaypoint.Mode getCurrentMode() {
            return mScriptPlayer == null ? null : mScriptPlayer.getMode();
        }

        public int getScriptState() {
            return mScriptState;
        }

        /** 当前正在播放的脚本 id；没在播放（含已跑完）时返回 null */
        public String getRunningScriptId() {
            if (mScriptState != SCRIPT_STATE_PLAYING || mScriptRoute == null) {
                return null;
            }
            return mScriptRoute.id;
        }

        public String getRunningScriptName() {
            return mScriptRoute == null ? null : mScriptRoute.name;
        }

        public int getRunningScriptPointCount() {
            return mScriptRoute == null ? 0 : mScriptRoute.size();
        }

        public void setScriptListener(ScriptListener listener) {
            mScriptListener = listener;
        }

        public void clearScriptListener() {
            mScriptListener = null;
        }

        /** 把当前位置同步给摇杆（例如脚本运行时界面重新绑定） */
        public void syncJoyStick() {
            if (mJoyStick != null) {
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            }
        }
    }
}

