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

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    /* 服务被系统重新拉起（intent 为 null）时，用这里保存的上次位置恢复，避免拿不到位置 */
    private static final String KEY_LAST_LNG = "service_last_lng";
    private static final String KEY_LAST_LAT = "service_last_lat";
    private static final String KEY_LAST_ALT = "service_last_alt";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;

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

        if (intent != null) {
            lng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
            lat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
            alt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);
        } else {
            // 服务被系统重新拉起时 intent 为 null（原实现在这里会直接 NPE 闪退），
            // 这里改用上次保存的位置，保证服务能正常恢复
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            lng = parseDouble(preferences.getString(KEY_LAST_LNG, null), DEFAULT_LNG);
            lat = parseDouble(preferences.getString(KEY_LAST_LAT, null), DEFAULT_LAT);
            alt = parseDouble(preferences.getString(KEY_LAST_ALT, null), DEFAULT_ALT);
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

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isStop = true;

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

            NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
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
                    .setSmallIcon(R.mipmap.ic_launcher)
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
                    mCurLng = lng;
                    mCurLat = lat;
                    mCurAlt = alt;
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
                        setLocationNetwork();
                        setLocationGPS();
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
            loc.setBearing(mCurBea);                       // 方向（度）
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
            loc.setBearing(mCurBea);                       // 方向（度）
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

            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            saveLastPosition();
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);

            if (mJoyStick != null) {
                mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
            }
        }
    }
}

