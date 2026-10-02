package com.zcshou.joystick;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.SearchView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import com.zcshou.database.DataBaseHistoryLocation;
import com.zcshou.gogogo.HistoryActivity;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.OsmGeocoder;
import com.zcshou.utils.TileSourceUtils;

import okhttp3.OkHttpClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JoyStick extends View {
    private static final int DivGo = 1000;    /* 移动的时间间隔，单位 ms */
    private static final int WINDOW_TYPE_JOYSTICK = 0;
    private static final int WINDOW_TYPE_MAP = 1;
    private static final int WINDOW_TYPE_HISTORY = 2;

    private final Context mContext;
    private WindowManager.LayoutParams mWindowParamCurrent;
    private WindowManager mWindowManager;
    private int mCurWin = WINDOW_TYPE_JOYSTICK;
    private final LayoutInflater inflater;
    private boolean isWalk;
    private ImageButton btnWalk;
    private boolean isRun;
    private ImageButton btnRun;
    private boolean isBike;
    private ImageButton btnBike;
    private JoyStickClickListener mListener;

    // 移动
    private View mJoystickLayout;
    private GoUtils.TimeCount mTimer;
    private boolean isMove;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private double mAltitude = 55.0;
    private double mAngle = 0;
    private double mR = 0;
    private double disLng = 0;
    private double disLat = 0;
    /** 方向输入控件（圆形摇杆 / 方向按键），点击移动开启时收起 */
    private RockerView mRockerView;
    private ButtonView mButtonView;
    /** 点击移动是否生效（与摇杆方向输入为切换关系） */
    private boolean mClickMoveEnabled;
    private final SharedPreferences sharedPreferences;
    /* 历史记录悬浮窗相关 */
    private FrameLayout mHistoryLayout;
    private final List<Map<String, Object>> mAllRecord = new ArrayList<> ();
    private TextView noRecordText;
    private ListView mRecordListView;
    /* 地图悬浮窗相关 */
    private FrameLayout mMapLayout;
    private MapView mMapView;
    /** 当前位置，BD-09（保持原工程内部坐标语义，绘制时转 WGS-84） */
    private GeoPoint mCurMapLngLat;
    /** 当前选点，BD-09 */
    private GeoPoint mMarkMapLngLat;
    private Marker mCurMarker;
    private Marker mMarkMarker;
    private OsmGeocoder mGeoCoder;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;

    public JoyStick(Context context) {
        super(context);
        mContext = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        inflater = LayoutInflater.from(context);
        initUi();
    }

    public JoyStick(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        inflater = LayoutInflater.from(context);
        initUi();
    }

    public JoyStick(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        inflater = LayoutInflater.from(context);
        initUi();
    }

    /**
     * 三个构造函数共用的初始化。
     *
     * <p>mContext / inflater / sharedPreferences 是 final 字段，只能在构造器里赋值，
     * 所以那三行留在各个构造器中；剩下的“建窗口 → 建三个悬浮视图”完全相同，
     * 抽到这里，以后改初始化顺序只改一处。</p>
     */
    private void initUi() {
        initWindowManager();

        if (inflater != null) {
            initJoyStickView();

            initJoyStickMapView();

            initHistoryView();
        }
    }

    public void setCurrentPosition(double lng, double lat, double alt) {
        double[] lngLat = MapUtils.wgs2bd09(lng, lat);
        mCurMapLngLat = new GeoPoint(lngLat[1], lngLat[0]);
        mAltitude = alt;

        resetMap();
    }

    public void show() {
        switch (mCurWin) {
            case WINDOW_TYPE_MAP:
                if (mJoystickLayout.getParent() != null) {
                    mWindowManager.removeView(mJoystickLayout);
                }
                if (mHistoryLayout.getParent() != null) {
                    mWindowManager.removeView(mHistoryLayout);
                }
                if (mMapLayout.getParent() == null) {
                    resetMap();
                    mWindowManager.addView(mMapLayout, mWindowParamCurrent);
                }
                break;
            case WINDOW_TYPE_HISTORY:
                if (mMapLayout.getParent() != null) {
                    mWindowManager.removeView(mMapLayout);
                }
                if (mJoystickLayout.getParent() != null) {
                    mWindowManager.removeView(mJoystickLayout);
                }
                if (mHistoryLayout.getParent() == null) {
                    mWindowManager.addView(mHistoryLayout, mWindowParamCurrent);
                }
                break;
            case WINDOW_TYPE_JOYSTICK:
                if (mMapLayout.getParent() != null) {
                    mWindowManager.removeView(mMapLayout);
                }
                if (mHistoryLayout.getParent() != null) {
                    mWindowManager.removeView(mHistoryLayout);
                }
                if (mJoystickLayout.getParent() == null) {
                    mWindowManager.addView(mJoystickLayout, mWindowParamCurrent);
                }
                break;
        }
    }

    public void hide() {
        if (mMapLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mMapLayout);
        }

        if (mJoystickLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mJoystickLayout);
        }

        if (mHistoryLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mHistoryLayout);
        }
    }

    public void destroy() {
        if (mMapLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mMapLayout);
        }

        if (mJoystickLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mJoystickLayout);
        }

        if (mHistoryLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mHistoryLayout);
        }

        if (mMapView != null) {
            mMapView.onDetach();
        }
    }

    public void setListener(JoyStickClickListener mListener) {
        this.mListener = mListener;
    }

    private void initWindowManager() {
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowParamCurrent = new WindowManager.LayoutParams();
        mWindowParamCurrent.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWindowParamCurrent.format = PixelFormat.RGBA_8888;
        mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE      // 不添加这个将导致游戏无法启动（MIUI12）,添加之后导致键盘无法显示
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowParamCurrent.gravity = Gravity.START | Gravity.TOP;
        mWindowParamCurrent.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.x = 300;
        mWindowParamCurrent.y = 300;
    }

    @SuppressLint("InflateParams")
    private void initJoyStickView() {
        /* 移动计时器 */
        mTimer = new GoUtils.TimeCount(DivGo, DivGo);
        mTimer.setListener(new GoUtils.TimeCount.TimeCountListener() {
            @Override
            public void onTick(long millisUntilFinished) {

            }

            @Override
            public void onFinish() {
                // 注意：这里的 x y 与 圆中角度的对应问题（以 X 轴正向为 0 度）且转换为 km
                disLng = mSpeed * (double)(DivGo / 1000) * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                disLat = mSpeed * (double)(DivGo / 1000) * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F-mAngle);
                mTimer.start();
            }
        });
        // 获取参数区设置的速度
        try {
            mSpeed = Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
        } catch (NumberFormatException e) {  // GOOD: The exception is caught.
            mSpeed = 1.2;
        }
        mJoystickLayout = inflater.inflate(R.layout.joystick, null);

        /* 整个摇杆拖动事件处理 */
        mJoystickLayout.setOnTouchListener(new JoyStickOnTouchListener());

        /* 位置按钮点击事件处理 */
        ImageButton btnPosition = mJoystickLayout.findViewById(R.id.joystick_position);
        btnPosition.setOnClickListener(v -> {
            if (mMapLayout.getParent() == null) {
                mCurWin = WINDOW_TYPE_MAP;
                show();
            }
        });

        /* 历史按钮点击事件处理 */
        ImageButton btnHistory = mJoystickLayout.findViewById(R.id.joystick_history);
        btnHistory.setOnClickListener(v -> {
            if (mHistoryLayout.getParent() == null) {
                mCurWin = WINDOW_TYPE_HISTORY;
                show();
            }
        });

        /* 步行按键的点击处理 */
        btnWalk = mJoystickLayout.findViewById(R.id.joystick_walk);
        btnWalk.setOnClickListener(v -> {
            if (!isWalk) {
                btnWalk.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isWalk = true;
                btnRun.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isRun = false;
                btnBike.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isBike = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 1.2;
                }
            }
        });
        /* 默认为步行 */
        isWalk = true;
        btnWalk.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
        /* 跑步按键的点击处理 */
        isRun = false;
        btnRun = mJoystickLayout.findViewById(R.id.joystick_run);
        btnRun.setOnClickListener(v -> {
            if (!isRun) {
                btnRun.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isRun = true;
                btnWalk.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isWalk = false;
                btnBike.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isBike = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_run", getResources().getString(R.string.setting_run_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 3.6;
                }
            }
        });
        /* 自行车按键的点击处理 */
        isBike = false;
        btnBike = mJoystickLayout.findViewById(R.id.joystick_bike);
        btnBike.setOnClickListener(v -> {
            if (!isBike) {
                btnBike.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isBike = true;
                btnWalk.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isWalk = false;
                btnRun.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isRun = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_bike", getResources().getString(R.string.setting_bike_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 10.0;
                }
            }
        });
        /* 方向键点击处理 */
        mRockerView = mJoystickLayout.findViewById(R.id.joystick_rocker);
        mRockerView.setListener(this::processDirection);

        /* 方向键点击处理 */
        mButtonView = mJoystickLayout.findViewById(R.id.joystick_button);
        mButtonView.setListener(this::processDirection);

        /* 这里用来决定移动类型（“移动类型”设置：0/2 圆形摇杆，1 方向按键；2=点击移动时先显示摇杆，
           服务连上后 setClickMoveEnabled 会按开关状态收起方向输入） */
        if (sharedPreferences.getString("setting_joystick_type", "0").equals("1")) {
            mRockerView.setVisibility(GONE);
            mButtonView.setVisibility(VISIBLE);
        } else {
            mRockerView.setVisibility(VISIBLE);
            mButtonView.setVisibility(GONE);
        }
    }

    /** 摇杆当前选择的行走速度（走 / 跑 / 骑按钮的结果），点击移动按它走 */
    public double getCurrentSpeed() {
        return mSpeed;
    }

    /**
     * 点击移动开关：开启时收起方向摇杆（点击与摇杆是切换关系），
     * 行走方式按钮保留——点击移动的行走速度取的正是这里的选择。
     */
    public void setClickMoveEnabled(boolean enabled) {
        mClickMoveEnabled = enabled;

        // 无论开关方向都先把移动计时器停掉：控件可能在按住途中被收起，
        // 收不到“抬起”事件，留着计时器会变成无人触碰也在移动的幽灵位移
        if (mTimer != null) {
            mTimer.cancel();
        }
        isMove = false;

        boolean useRocker = !"1".equals(sharedPreferences.getString("setting_joystick_type", "0"));

        if (mRockerView != null) {
            mRockerView.setVisibility(enabled ? GONE : (useRocker ? VISIBLE : GONE));
        }
        if (mButtonView != null) {
            mButtonView.setVisibility(enabled ? GONE : (useRocker ? GONE : VISIBLE));
        }
    }

    private void processDirection(boolean auto, double angle, double r) {
        // 点击移动开启时方向输入整体无效（两者为切换关系）。
        // 顺带取消移动计时器：否则“手指还按着时切换模式导致控件收起、收不到抬起事件”，
        // 计时器会一直空转，等切回摇杆就会在无人触碰时自己动（幽灵位移）
        if (mClickMoveEnabled) {
            mTimer.cancel();
            isMove = false;
            return;
        }

        if (r <= 0) {
            mTimer.cancel();
            isMove = false;
        } else {
            mAngle = angle;
            mR = r;
            if (auto) {
                if (!isMove) {
                    mTimer.start();
                    isMove = true;
                }
            } else {
                mTimer.cancel();
                isMove = false;
                // 注意：这里的 x y 与 圆中角度的对应问题（以 X 轴正向为 0 度）且转换为 km
                disLng = mSpeed * (double)(DivGo / 1000) * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                disLat = mSpeed * (double)(DivGo / 1000) * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F-mAngle);
            }
        }
    }

    private class JoyStickOnTouchListener implements OnTouchListener {
        private int x;
        private int y;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x = (int) event.getRawX();
                    y = (int) event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int nowX = (int) event.getRawX();
                    int nowY = (int) event.getRawY();
                    int movedX = nowX - x;
                    int movedY = nowY - y;
                    x = nowX;
                    y = nowY;

                    mWindowParamCurrent.x += movedX;
                    mWindowParamCurrent.y += movedY;
                    mWindowManager.updateViewLayout(view, mWindowParamCurrent);
                    break;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    public interface JoyStickClickListener {
        void onMoveInfo(double speed, double disLng, double disLat, double angle);
        void onPositionInfo(double lng, double lat, double alt);
    }


    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void initJoyStickMapView() {
        mMapLayout = (FrameLayout)inflater.inflate(R.layout.joystick_map, null);
        mMapLayout.setOnTouchListener(new JoyStickOnTouchListener());

        mSearchList = mMapLayout.findViewById(R.id.map_search_list_view);
        mSearchLayout = mMapLayout.findViewById(R.id.map_search_linear);
        // 地点检索改用 OpenStreetMap Nominatim（替代百度 SuggestionSearch）
        mGeoCoder = new OsmGeocoder(new OkHttpClient());
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            mSearchLayout.setVisibility(View.GONE);

            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            markMap(new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lng)));
        });

        TextView tips = mMapLayout.findViewById(R.id.joystick_map_tips);
        SearchView mSearchView = mMapLayout.findViewById(R.id.joystick_map_searchView);
        mSearchView.setOnSearchClickListener(v -> {
            tips.setVisibility(GONE);

            // 特殊处理：这里让搜索框获取焦点，以显示输入法
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);
        });
        mSearchView.setOnCloseListener(() -> {
            tips.setVisibility(VISIBLE);
            mSearchLayout.setVisibility(GONE);

            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);

            return false;       /* 这里必须返回false，否则需要自行处理搜索框的折叠 */
        });
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText != null && newText.length() > 0) {
                    try {
                        doSearch(newText);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(mContext,getResources().getString(R.string.app_error_search));
                        e.printStackTrace();
                    }
                } else {
                    mSearchLayout.setVisibility(GONE);
                }

                return true;
            }
        });

        ImageButton btnGo = mMapLayout.findViewById(R.id.btnGo);
        btnGo.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);

            tips.setVisibility(VISIBLE);
            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();

            if (mMarkMapLngLat == null) {
                GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_error_location));
            } else {
                if (mCurMapLngLat != mMarkMapLngLat) {
                    mCurMapLngLat = mMarkMapLngLat;
                    mMarkMapLngLat = null;

                    double[] lngLat = MapUtils.bd2wgs(mCurMapLngLat.getLongitude(), mCurMapLngLat.getLatitude());
                    mListener.onPositionInfo(lngLat[0], lngLat[1], mAltitude);

                    resetMap();

                    GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_location_ok));
                }
            }
        });
        btnGo.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));

        ImageButton btnClose = mMapLayout.findViewById(R.id.map_close);
        btnClose.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            tips.setVisibility(VISIBLE);
            mSearchLayout.setVisibility(GONE);
            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();

            mCurWin = WINDOW_TYPE_JOYSTICK;
            show();
        });

        ImageButton btnBack = mMapLayout.findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> resetMap());
        btnBack.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));

        initOsmMap();
    }

    private void initOsmMap() {
        mMapView = mMapLayout.findViewById(R.id.map_joystick);
        mMapView.setBuiltInZoomControls(false);
        mMapView.setMultiTouchControls(true);
        mMapView.setTilesScaledToDpi(true);
        // 默认使用 OpenStreetMap，与主界面保持一致
        mMapView.setTileSource(TileSourceUtils.OSM_STANDARD);
        mMapView.getController().setZoom(18.0);

        // 当前位置标记：对应原实现里通过 setMyLocationData 显示的“我的位置”
        mCurMarker = createMarker(ContextCompat.getDrawable(mContext, R.drawable.ic_home_position), 0.5f);
        if (mCurMarker != null) {
            mMapView.getOverlays().add(mCurMarker);
        }

        // 选点标记
        mMarkMarker = createMarker(ContextCompat.getDrawable(mContext, MainActivity.MAP_INDICATOR_RES), 1.0f);
        if (mMarkMarker != null) {
            mMapView.getOverlays().add(mMarkMarker);
        }

        // 地图单击 / 长按（回调坐标为 WGS-84）。双击缩放由 osmdroid 内置处理。
        mMapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            /**
             * 单击地图
             */
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                markMap(wgs84ToBd09(p.getLongitude(), p.getLatitude()));
                return true;
            }

            /**
             * 长按地图
             */
            @Override
            public boolean longPressHelper(GeoPoint p) {
                markMap(wgs84ToBd09(p.getLongitude(), p.getLatitude()));
                return true;
            }
        }));
    }

    /**
     * 创建地图标记。osmdroid 的 Marker 需要各自持有一份 Drawable，所以每次从 ConstantState 新建。
     *
     * @param anchorV 图标垂直锚点，1.0f 表示图标底部对准坐标（图钉样式）
     */
    private Marker createMarker(Drawable drawable, float anchorV) {
        if (mMapView == null || drawable == null) {
            return null;
        }
        Drawable icon = drawable;
        if (drawable.getConstantState() != null) {
            icon = drawable.getConstantState().newDrawable();
        }
        Marker marker = new Marker(mMapView);
        marker.setIcon(icon);
        marker.setAnchor(0.5f, anchorV);
        return marker;
    }

    private void resetMap() {
        if (mMapView == null) {
            return;
        }

        // 清除选点：osmdroid 的 Marker 不接受 null 坐标（内部会做位置运算，直接 NPE），
        // 这里把标记从图层里摘掉，选点时再放回去
        clearMark();

        if (mCurMapLngLat != null) {
            GeoPoint wgs84 = bd09ToWgs84(mCurMapLngLat);
            if (mCurMarker != null) {
                mCurMarker.setPosition(wgs84);
            }
            mMapView.getController().setZoom(18.0);
            mMapView.getController().animateTo(wgs84);
        }

        mMapView.invalidate();
    }

    private void markMap(GeoPoint bd09Point) {
        if (mMapView == null || bd09Point == null) {
            return;
        }

        mMarkMapLngLat = bd09Point;
        GeoPoint wgs84 = bd09ToWgs84(bd09Point);

        if (mMarkMarker != null) {
            mMarkMarker.setEnabled(true);
            if (!mMapView.getOverlays().contains(mMarkMarker)) {
                mMapView.getOverlays().add(mMarkMarker);
            }
            mMarkMarker.setPosition(wgs84);
        }

        mMapView.getController().setZoom(18.0);
        mMapView.getController().animateTo(wgs84);
        mMapView.invalidate();
    }

    /** 摘掉选点标记（不销毁对象，选点时再放回去） */
    private void clearMark() {
        if (mMapView == null || mMarkMarker == null) {
            return;
        }

        try {
            mMarkMarker.closeInfoWindow();
            mMarkMarker.setEnabled(false);
            mMapView.getOverlays().remove(mMarkMarker);
        } catch (Exception e) {
            Log.e("JOYSTICK", "ERROR - clearMark");
        }
    }

    /**
     * BD-09 -> WGS-84（osmdroid 的瓦片是 WGS-84）
     */
    private static GeoPoint bd09ToWgs84(GeoPoint bd09) {
        double[] wgs84 = MapUtils.bd2wgs(bd09.getLongitude(), bd09.getLatitude());
        return new GeoPoint(wgs84[1], wgs84[0]);
    }

    /**
     * WGS-84 -> BD-09（保持工程内部原有的坐标语义）
     */
    private static GeoPoint wgs84ToBd09(double lng, double lat) {
        double[] bd09 = MapUtils.wgs2bd09(lng, lat);
        return new GeoPoint(bd09[1], bd09[0]);
    }

    /**
     * 关键字检索（OpenStreetMap Nominatim），结果统一转成 BD-09 后再填入列表
     */
    private void doSearch(String keyword) {
        mGeoCoder.search(keyword, pois -> {
            if (pois == null || pois.isEmpty()) {
                GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_search_null));
                return;
            }

            List<Map<String, Object>> data = new ArrayList<>();
            for (OsmGeocoder.Poi poi : pois) {
                double[] bd09 = MapUtils.wgs2bd09(poi.longitude, poi.latitude);

                Map<String, Object> poiItem = new HashMap<>();
                poiItem.put(MainActivity.POI_NAME, poi.name);
                poiItem.put(MainActivity.POI_ADDRESS, poi.address);
                poiItem.put(MainActivity.POI_LONGITUDE, "" + bd09[0]);
                poiItem.put(MainActivity.POI_LATITUDE, "" + bd09[1]);
                data.add(poiItem);
            }

            SimpleAdapter simAdapt = new SimpleAdapter(
                    mContext,
                    data,
                    R.layout.search_poi_item,
                    new String[] {MainActivity.POI_NAME, MainActivity.POI_ADDRESS, MainActivity.POI_LONGITUDE, MainActivity.POI_LATITUDE}, // 与下面数组元素要一一对应
                    new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
            mSearchList.setAdapter(simAdapt);
            mSearchLayout.setVisibility(View.VISIBLE);
        });
    }


    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void initHistoryView() {
        mHistoryLayout = (FrameLayout)inflater.inflate(R.layout.joystick_history, null);
        mHistoryLayout.setOnTouchListener(new JoyStickOnTouchListener());

        TextView tips = mHistoryLayout.findViewById(R.id.joystick_his_tips);
        SearchView mSearchView = mHistoryLayout.findViewById(R.id.joystick_his_searchView);
        mSearchView.setOnSearchClickListener(v -> {
            tips.setVisibility(GONE);

            // 特殊处理：这里让搜索框获取焦点，以显示输入法
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);
        });
        mSearchView.setOnCloseListener(() -> {
            tips.setVisibility(VISIBLE);

            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);

            return false;       /* 这里必须返回false，否则需要自行处理搜索框的折叠 */
        });
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {// 当点击搜索按钮时触发该方法
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {// 当搜索内容改变时触发该方法
                if (TextUtils.isEmpty(newText)) {
                    showHistory(mAllRecord);
                } else {
                    List<Map<String, Object>> searchRet = new ArrayList<>();
                    for (int i = 0; i < mAllRecord.size(); i++){
                        if (mAllRecord.get(i).toString().indexOf(newText) > 0){
                            searchRet.add(mAllRecord.get(i));
                        }
                    }

                    if (searchRet.size() > 0) {
                        showHistory(searchRet);
                    } else {
                        GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_search_null));
                        showHistory(mAllRecord);
                    }
                }

                return false;
            }
        });

        noRecordText = mHistoryLayout.findViewById(R.id.joystick_his_record_no_textview);
        mRecordListView = mHistoryLayout.findViewById(R.id.joystick_his_record_list_view);
        mRecordListView.setOnItemClickListener((adapterView, view, i, l) -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);

            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();
            tips.setVisibility(VISIBLE);

            // wgs84坐标
            String wgs84LatLng = (String) ((TextView) view.findViewById(R.id.WGSLatLngText)).getText();
            wgs84LatLng = wgs84LatLng.substring(wgs84LatLng.indexOf('[') + 1, wgs84LatLng.indexOf(']'));
            String[] wgs84latLngStr = wgs84LatLng.split(" ");
            String wgs84Longitude = wgs84latLngStr[0].substring(wgs84latLngStr[0].indexOf(':') + 1);
            String wgs84Latitude = wgs84latLngStr[1].substring(wgs84latLngStr[1].indexOf(':') + 1);

            mListener.onPositionInfo(Double.parseDouble(wgs84Longitude), Double.parseDouble(wgs84Latitude), mAltitude);

            // 注意这里在选择位置之后需要刷新地图
            String bdLatLng = (String) ((TextView) view.findViewById(R.id.BDLatLngText)).getText();
            bdLatLng = bdLatLng.substring(bdLatLng.indexOf('[') + 1, bdLatLng.indexOf(']'));
            String[] bdLatLngStr = bdLatLng.split(" ");
            String bdLongitude = bdLatLngStr[0].substring(bdLatLngStr[0].indexOf(':') + 1);
            String bdLatitude = bdLatLngStr[1].substring(bdLatLngStr[1].indexOf(':') + 1);
            mCurMapLngLat = new GeoPoint(Double.parseDouble(bdLatitude), Double.parseDouble(bdLongitude));

            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_location_ok));
        });

        fetchAllRecord();

        showHistory(mAllRecord);

        ImageButton btnClose = mHistoryLayout.findViewById(R.id.joystick_his_close);
        btnClose.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();
            tips.setVisibility(VISIBLE);

            mCurWin = WINDOW_TYPE_JOYSTICK;
            show();
        });
    }

    private void fetchAllRecord() {
        SQLiteDatabase mHistoryLocationDB;

        try {
            DataBaseHistoryLocation hisLocDBHelper = new DataBaseHistoryLocation(mContext.getApplicationContext());
            mHistoryLocationDB = hisLocDBHelper.getWritableDatabase();

            Cursor cursor = mHistoryLocationDB.query(DataBaseHistoryLocation.TABLE_NAME, null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", new String[] {"0"},
                    null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> item = new HashMap<>();
                int ID = cursor.getInt(0);
                String Location = cursor.getString(1);
                String Longitude = cursor.getString(2);
                String Latitude = cursor.getString(3);
                long TimeStamp = cursor.getInt(4);
                String BD09Longitude = cursor.getString(5);
                String BD09Latitude = cursor.getString(6);
                Log.d("TB", ID + "\t" + Location + "\t" + Longitude + "\t" + Latitude + "\t" + TimeStamp + "\t" + BD09Longitude + "\t" + BD09Latitude);
                BigDecimal bigDecimalLongitude = BigDecimal.valueOf(Double.parseDouble(Longitude));
                BigDecimal bigDecimalLatitude = BigDecimal.valueOf(Double.parseDouble(Latitude));
                BigDecimal bigDecimalBDLongitude = BigDecimal.valueOf(Double.parseDouble(BD09Longitude));
                BigDecimal bigDecimalBDLatitude = BigDecimal.valueOf(Double.parseDouble(BD09Latitude));
                double doubleLongitude = bigDecimalLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleLatitude = bigDecimalLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLongitude = bigDecimalBDLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLatitude = bigDecimalBDLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                item.put(HistoryActivity.KEY_ID, Integer.toString(ID));
                item.put(HistoryActivity.KEY_LOCATION, Location);
                item.put(HistoryActivity.KEY_TIME, GoUtils.timeStamp2Date(Long.toString(TimeStamp)));
                item.put(HistoryActivity.KEY_LNG_LAT_WGS, "[经度:" + doubleLongitude + " 纬度:" + doubleLatitude + "]");
                item.put(HistoryActivity.KEY_LNG_LAT_CUSTOM, "[经度:" + doubleBDLongitude + " 纬度:" + doubleBDLatitude + "]");
                mAllRecord.add(item);
            }
            cursor.close();
            mHistoryLocationDB.close();
        } catch (Exception e) {
            Log.e("JOYSTICK", "ERROR - fetchAllRecord");
        }
    }

    private void showHistory(List<Map<String, Object>> list) {
        if (list.size() == 0) {
            mRecordListView.setVisibility(View.GONE);
            noRecordText.setVisibility(View.VISIBLE);
        } else {
            noRecordText.setVisibility(View.GONE);
            mRecordListView.setVisibility(View.VISIBLE);

            try {
                SimpleAdapter simAdapt = new SimpleAdapter(
                        mContext,
                        list,
                        R.layout.history_item,
                        new String[]{HistoryActivity.KEY_ID, HistoryActivity.KEY_LOCATION, HistoryActivity.KEY_TIME, HistoryActivity.KEY_LNG_LAT_WGS, HistoryActivity.KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                        new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                mRecordListView.setAdapter(simAdapt);
            } catch (Exception e) {
                Log.e("JOYSTICK", "ERROR - showHistory");
            }
        }
    }
}