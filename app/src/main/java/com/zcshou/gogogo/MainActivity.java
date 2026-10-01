package com.zcshou.gogogo;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.infowindow.InfoWindow;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zcshou.service.ServiceGo;
import com.zcshou.database.DataBaseHistoryLocation;
import com.zcshou.database.DataBaseHistorySearch;
import com.zcshou.script.ScriptParser;
import com.zcshou.script.ScriptRoute;
import com.zcshou.script.ScriptStore;
import com.zcshou.script.ScriptWaypoint;
import com.zcshou.utils.ShareUtils;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.OsmGeocoder;
import com.zcshou.utils.TileSourceUtils;

import com.elvishew.xlog.XLog;

import io.noties.markwon.Markwon;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends BaseActivity implements SensorEventListener {
    /* 对外 */
    public static final String LAT_MSG_ID = "LAT_VALUE";
    public static final String LNG_MSG_ID = "LNG_VALUE";
    public static final String ALT_MSG_ID = "ALT_VALUE";

    public static final String POI_NAME = "POI_NAME";
    public static final String POI_ADDRESS = "POI_ADDRESS";
    public static final String POI_LONGITUDE = "POI_LONGITUDE";
    public static final String POI_LATITUDE = "POI_LATITUDE";

    /** 启动脚本模式时传给 ServiceGo 的脚本 id */
    public static final String SCRIPT_ROUTE_ID = "SCRIPT_ROUTE_ID";

    private OkHttpClient mOkHttpClient;
    private SharedPreferences sharedPreferences;

    /*============================== 主界面地图 相关 ==============================*/
    /************** 地图 *****************/
    /**
     * 选点标记的图标资源。osmdroid 的每个 Marker 需要各自持有 Drawable，
     * 所以这里只暴露资源 id，由使用方自行创建。
     */
    public final static int MAP_INDICATOR_RES = R.drawable.icon_gcoding;
    /**
     * 当前标记的地图点。
     * 注意：这里存放的始终是 <b>BD-09</b> 坐标（与原实现保持一致），
     * 只有在绘制到地图、以及地图回调返回坐标时，才通过 {@link MapUtils} 做 BD-09 / WGS-84 转换。
     */
    private static GeoPoint mMarkLatLngMap = new GeoPoint(36.547743718042415, 117.07018449827267);
    private static String mMarkName = null;
    /** 主界面地图（static 是为了让 HistoryActivity 能通过静态方法 showLocation 回填选点） */
    private static MapView mMapView;
    private static Marker mMarkMarker = null;
    private static Drawable sMapIndicatorDrawable = null;
    private OsmGeocoder mGeoCoder;
    private MyLocationNewOverlay mMyLocationOverlay;
    private LocationManager mSysLocManager;
    private LocationListener mSysLocListener;
    private SensorManager mSensorManager;
    private Sensor mSensorAccelerometer;
    private Sensor mSensorMagnetic;
    private float[] mAccValues = new float[3];//加速度传感器数据
    private float[] mMagValues = new float[3];//地磁传感器数据
    private final float[] mR = new float[9];//旋转矩阵，用来保存磁场和加速度的数据
    private final float[] mDirectionValues = new float[3];//模拟方向传感器的数据（原始数据为弧度）
    /************** 定位 *****************/
    private double mCurrentLat = 0.0;       // 当前位置的纬度（WGS-84）
    private double mCurrentLon = 0.0;       // 当前位置的经度（WGS-84）
    private float mCurrentDirection = 0.0f;
    private boolean isFirstLoc = true; // 是否首次定位
    private boolean isMockServStart = false;
    private ServiceGo.ServiceGoBinder mServiceBinder;
    /** 是否已经绑定过 ServiceGo（与 isMockServStart 分开记，退出时要保证解绑） */
    private boolean mBoundToService = false;
    private ServiceConnection mConnection;
    private FloatingActionButton mButtonStart;
    /** 脚本模式入口按钮 */
    private FloatingActionButton mButtonScript;
    /** 当前正在播放的脚本 id，null 表示没有在播放脚本（由定位线程回调更新） */
    private volatile String mRunningScriptId;
    /*============================== 主界面连续选点（连贯移动） ==============================*/
    /**
     * 主界面选点生成的路线在脚本存储里的固定 id。
     * 每次重新选点都覆盖同一条，避免脚本列表里堆满“地图路线”。
     */
    private static final String MAP_ROUTE_ID = "map_route_from_main_map";
    /** 路线选点模式开关：开启时点地图是“往路线末尾加一个路点”，而不是原来的“选一个传送点” */
    private boolean mRoutePicking = false;
    /** 已选路点（WGS-84，与 ServiceGo / ScriptPlayer 使用的坐标语义一致） */
    private final List<ScriptWaypoint> mRoutePoints = new ArrayList<>();
    /** 新路点使用的状态（步行 / 跑步 / 骑行），速度取“设置”里对应的值 */
    private ScriptWaypoint.Mode mRouteMode = ScriptWaypoint.Mode.WALK;
    private FloatingActionButton mButtonRoute;
    private LinearLayout mRouteBar;
    private TextView mRouteHint;
    private Spinner mRouteModeSpinner;
    private Button mRouteGoButton;
    /** 路线连线：点地图加路点时实时重画 */
    private Polyline mRouteLine;
    private final List<Marker> mRouteMarkers = new ArrayList<>();
    /** 地图点击派发器：始终保持在图层最上面，保证点地图不会被路线图钉 / 连线吃掉 */
    private MapEventsOverlay mMapEventsOverlay;
    /*============================== 历史记录 相关 ==============================*/
    private SQLiteDatabase mLocationHistoryDB;
    private SQLiteDatabase mSearchHistoryDB;
    /*============================== SearchView 相关 ==============================*/
    private SearchView searchView;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    private ListView mSearchHistoryList;
    private LinearLayout mHistoryLayout;
    private MenuItem searchItem;
    /*============================== 更新 相关 ==============================*/
    private DownloadManager mDownloadManager = null;
    private long mDownloadId;
    private BroadcastReceiver mDownloadBdRcv;
    private String mUpdateFilename;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.nav_drawer_open, R.string.nav_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        XLog.i("MainActivity: onCreate");

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mOkHttpClient = new OkHttpClient();

        initNavigationView();

        initMap();

        initMapLocation();

        initMapButton();

        initGoBtn();

        initRoutePicking();

        // 恢复旋屏前还没开始移动的选点
        if (savedInstanceState != null) {
            mRoutePoints.clear();
            mRoutePoints.addAll(ScriptStore.decodePoints(savedInstanceState.getString(STATE_ROUTE_POINTS, "")));
            ScriptWaypoint.Mode[] modes = ScriptWaypoint.Mode.values();
            int modeOrdinal = savedInstanceState.getInt(STATE_ROUTE_MODE, 0);
            if (modeOrdinal >= 0 && modeOrdinal < modes.length) {
                mRouteMode = modes[modeOrdinal];
            }
            // 恢复“正在选点”的界面状态，但不弹提示（旋屏不该再提示一次）
            if (savedInstanceState.getBoolean(STATE_ROUTE_PICKING, false)) {
                mRoutePicking = true;
                if (mRouteBar != null) {
                    mRouteBar.setVisibility(View.VISIBLE);
                }
                if (mButtonRoute != null) {
                    mButtonRoute.setImageResource(R.drawable.ic_close);
                }
            }
            redrawRoute();
        }

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = (ServiceGo.ServiceGoBinder)service;
                // 脚本播放状态变化时刷新入口按钮（脚本播放可能在脚本模式页面里启动）
                mServiceBinder.setScriptListener(mScriptListener);
                updateScriptButton();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        };

        initStoreHistory();

        initSearchView();

        initUpdateVersion();

        checkUpdateVersion(false);
    }

    @Override
    protected void onPause() {
        XLog.i("MainActivity: onPause");
        if (mMapView != null) {
            mMapView.onPause();
        }
        unregisterSensorListener();
        super.onPause();
    }

    /** 旋屏 / 重建时保存的“还没开始移动的路线选点” */
    private static final String STATE_ROUTE_POINTS = "state_route_points";
    private static final String STATE_ROUTE_MODE = "state_route_mode";
    private static final String STATE_ROUTE_PICKING = "state_route_picking";

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // 选点还没变成一条真正在跑的路线，只有内存里这一份，不存就白点一遍
        outState.putString(STATE_ROUTE_POINTS, ScriptStore.encodePoints(mRoutePoints));
        outState.putInt(STATE_ROUTE_MODE, mRouteMode.ordinal());
        outState.putBoolean(STATE_ROUTE_PICKING, mRoutePicking);
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        if (mMapView != null) {
            mMapView.onResume();
        }
        if (mSensorManager != null && mSensorAccelerometer != null) {
            mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (mSensorManager != null && mSensorMagnetic != null) {
            mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
        }

        // 回到主界面时同步一次脚本播放状态（脚本可能是在脚本模式页面里启动的）
        String running = new ScriptStore(this).getRunningScriptId();
        if (running != null) {
            mRunningScriptId = running;
        }
        updateScriptButton();

        super.onResume();
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        //取消注册传感器监听
        unregisterSensorListener();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        XLog.i("MainActivity: onDestroy");

        if (isMockServStart) {
            try {
                unbindServiceIfNeeded();
                Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
                stopService(serviceGoIntent);
            } catch (Exception e) {
                XLog.e("ERROR: stop ServiceGo");
            }
            isMockServStart = false;
        } else {
            unbindServiceIfNeeded();
        }

        try {
            unregisterReceiver(mDownloadBdRcv);
        } catch (Exception e) {
            XLog.e("ERROR: unregisterReceiver");
        }

        unregisterSensorListener();

        // 退出时销毁定位
        if (mSysLocManager != null && mSysLocListener != null) {
            try {
                mSysLocManager.removeUpdates(mSysLocListener);
            } catch (Exception e) {
                XLog.e("ERROR: removeUpdates");
            }
        }
        // 关闭定位图层
        if (mMyLocationOverlay != null) {
            try {
                mMyLocationOverlay.disableMyLocation();
            } catch (Exception e) {
                XLog.e("ERROR: disableMyLocation");
            }
        }
        if (mMapView != null) {
            mMapView.onDetach();
        }

        //close db
        if (mLocationHistoryDB != null) {
            mLocationHistoryDB.close();
        }
        if (mSearchHistoryDB != null) {
            mSearchHistoryDB.close();
        }

        super.onDestroy();
    }

    /** 注销传感器监听（mSensorManager 可能因为初始化失败而为空） */
    private void unregisterSensorListener() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(false);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        //找到searchView
        searchItem = menu.findItem(R.id.action_search);
        searchItem.setOnActionExpandListener(new  MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                mHistoryLayout.setVisibility(View.INVISIBLE);
                return true;  // Return true to collapse action view
            }
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchLayout.setVisibility(View.INVISIBLE);
                //展示搜索历史
                List<Map<String, Object>> data = getSearchHistory();

                if (!data.isEmpty()) {
                    SimpleAdapter simAdapt = new SimpleAdapter(
                            MainActivity.this,
                            data,
                            R.layout.search_item,
                            new String[] {DataBaseHistorySearch.DB_COLUMN_KEY,
                                    DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                    DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                    DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                    DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                    DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM},
                            new int[] {R.id.search_key,
                                    R.id.search_description,
                                    R.id.search_timestamp,
                                    R.id.search_isLoc,
                                    R.id.search_longitude,
                                    R.id.search_latitude});
                    mSearchHistoryList.setAdapter(simAdapt);
                    mHistoryLayout.setVisibility(View.VISIBLE);
                }

                return true;  // Return true to expand action view
            }
        });

        searchView = (SearchView) searchItem.getActionView();
        searchView.setIconified(false);// 设置searchView处于展开状态
        searchView.onActionViewExpanded();// 当展开无输入内容的时候，没有关闭的图标
        searchView.setIconifiedByDefault(true);//默认为true在框内，设置false则在框外
        searchView.setSubmitButtonEnabled(false);//显示提交按钮
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                try {
                    doSearch(query);
                    //搜索历史 插表参数
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, query);
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, "搜索关键字");
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_KEY);
                    contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

                    DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
                    clearMark(mMapView, mMarkMarker);
                    mSearchLayout.setVisibility(View.INVISIBLE);
                } catch (Exception e) {
                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                    XLog.d(getResources().getString(R.string.app_error_search));
                }

                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                //当输入框内容改变的时候回调
                //搜索历史置为不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);

                if (newText != null && !newText.isEmpty()) {
                    try {
                        doSearch(newText);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_search));
                        XLog.d(getResources().getString(R.string.app_error_search));
                    }
                }

                return true;
            }
        });

        // 搜索框的清除按钮(该按钮属于安卓系统图标)
        ImageView closeButton = searchView.findViewById(androidx.appcompat.R.id.search_close_btn);
        closeButton.setOnClickListener(v -> {
            EditText et = findViewById(androidx.appcompat.R.id.search_src_text);
            et.setText("");
            searchView.setQuery("", false);
            mSearchLayout.setVisibility(View.INVISIBLE);
            mHistoryLayout.setVisibility(View.VISIBLE);
        });

        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            mAccValues = sensorEvent.values;
        }
        else if(sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            mMagValues = sensorEvent.values;
        }

        SensorManager.getRotationMatrix(mR, null, mAccValues, mMagValues);
        SensorManager.getOrientation(mR, mDirectionValues);
        mCurrentDirection = (float) Math.toDegrees(mDirectionValues[0]);    // 弧度转角度
        if (mCurrentDirection < 0) {    // 由 -180 ~ + 180 转为 0 ~ 360
            mCurrentDirection += 360;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /*============================== NavigationView 相关 ==============================*/
    private void initNavigationView() {
        /*============================== NavigationView 相关 ==============================*/
        NavigationView mNavigationView = findViewById(R.id.nav_view);
        mNavigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_history) {
                Intent intent = new Intent(MainActivity.this, HistoryActivity.class);

                startActivity(intent);
            } else if (id == R.id.nav_script) {
                Intent intent = new Intent(MainActivity.this, ScriptActivity.class);

                startActivity(intent);
            } else if (id == R.id.nav_settings) {
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(intent);
            } else if (id == R.id.nav_dev) {
                if (!GoUtils.isDeveloperOptionsEnabled(this)) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                } else {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_dev));
                    }
                }
            } else if (id == R.id.nav_update) {
                checkUpdateVersion(true);
            } else if (id == R.id.nav_feedback) {
                File file = new File(getExternalFilesDir("Logs"), GoApplication.LOG_FILE_NAME);
                ShareUtils.shareFile(this, file, item.getTitle().toString());
            } else if (id == R.id.nav_contact) {
                Uri uri = Uri.parse("https://gitee.com/itexp/gogogo/issues");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            }

            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            drawer.closeDrawer(GravityCompat.START);

            return true;
        });

        // 直接获取第 0 个头部视图
        View headerView = mNavigationView.getHeaderView(0);
        TextView app_version = headerView.findViewById(R.id.app_version);
        app_version.setText(GoUtils.getVersionName(this));
    }

    /*============================== 主界面地图 相关 ==============================*/
    private void initMap() {
        // 地图初始化
        mMapView = findViewById(R.id.map_main);
        mMapView.setBuiltInZoomControls(false);
        mMapView.setMultiTouchControls(true);
        mMapView.setTilesScaledToDpi(true);
        // 默认使用 OpenStreetMap
        mMapView.setTileSource(TileSourceUtils.OSM_STANDARD);
        mMapView.getController().setZoom(18.0);

        sMapIndicatorDrawable = ContextCompat.getDrawable(this, MAP_INDICATOR_RES);

        // 地点检索 / 逆地理编码（OpenStreetMap Nominatim）
        mGeoCoder = new OsmGeocoder(mOkHttpClient);

        View poiView = View.inflate(MainActivity.this, R.layout.location_poi_info, null);
        TextView poiAddress = poiView.findViewById(R.id.poi_address);
        TextView poiLongitude = poiView.findViewById(R.id.poi_longitude);
        TextView poiLatitude = poiView.findViewById(R.id.poi_latitude);
        ImageButton ibSave = poiView.findViewById(R.id.poi_save);
        ibSave.setOnClickListener(v -> {
            recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_location_save));
        });
        ImageButton ibCopy = poiView.findViewById(R.id.poi_copy);
        ibCopy.setOnClickListener(v -> {
            //获取剪贴板管理器：
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建普通字符型ClipData
            ClipData mClipData = ClipData.newPlainText("Label",
                    mMarkLatLngMap.getLongitude() + "," + mMarkLatLngMap.getLatitude());
            // 将 ClipData内容放到系统剪贴板里。
            cm.setPrimaryClip(mClipData);

            GoUtils.DisplayToast(this,  getResources().getString(R.string.app_location_copy));
        });
        ImageButton ibShare = poiView.findViewById(R.id.poi_share);
        ibShare.setOnClickListener(v -> ShareUtils.shareText(MainActivity.this, "分享位置", poiLongitude.getText()+","+poiLatitude.getText()));
        ImageButton ibFly = poiView.findViewById(R.id.poi_fly);
        ibFly.setOnClickListener(this::doGoLocation);
        // 选点标记
        mMarkMarker = createMarkMarker();
        if (mMarkMarker != null) {
            mMarkMarker.setInfoWindow(new PoiInfoWindow(poiView, mMapView));
            // 单击标记默认会弹出信息气泡，这里屏蔽掉，只在长按逆地理编码成功后主动弹出
            mMarkMarker.setOnMarkerClickListener((marker, mapView) -> true);
            mMapView.getOverlays().add(mMarkMarker);
        }

        // 地图单击 / 长按事件。osmdroid 通过 MapEventsOverlay 派发，回调里拿到的是 WGS-84 坐标；
        // 双击缩放是 osmdroid 内置行为，不需要额外代码。
        MapEventsOverlay eventsOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            /**
             * 单击地图
             */
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                // 路线选点模式：点地图 = 往路线末尾加一个路点，不改动原来的单点传送标记
                if (mRoutePicking) {
                    addRoutePoint(p.getLongitude(), p.getLatitude());
                    return true;
                }

                mMarkLatLngMap = wgs84ToBd09(p.getLongitude(), p.getLatitude());
                markMap();
                return true;
            }

            /**
             * 长按地图
             */
            @Override
            public boolean longPressHelper(GeoPoint p) {
                if (mRoutePicking) {
                    addRoutePoint(p.getLongitude(), p.getLatitude());
                    return true;
                }

                mMarkLatLngMap = wgs84ToBd09(p.getLongitude(), p.getLatitude());
                markMap();
                // 逆地理编码，传入 WGS-84 坐标
                mGeoCoder.reverse(p.getLatitude(), p.getLongitude(), poi -> {
                    if (poi == null) {
                        XLog.i("逆地理位置失败!");
                        return;
                    }
                    mMarkName = String.valueOf(poi.name);
                    // 界面上一律沿用原有的 BD-09 语义展示
                    GeoPoint bd09 = wgs84ToBd09(poi.longitude, poi.latitude);
                    poiLatitude.setText(String.valueOf(bd09.getLatitude()));
                    poiLongitude.setText(String.valueOf(bd09.getLongitude()));
                    poiAddress.setText(poi.address);
                    if (mMarkMarker != null) {
                        mMarkMarker.setTitle(poi.address);
                        mMarkMarker.setSnippet(poi.name);
                        mMarkMarker.showInfoWindow();
                    }
                });
                return true;
            }
        });
        mMapEventsOverlay = eventsOverlay;
        mMapView.getOverlays().add(eventsOverlay);

        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);// 获取传感器管理服务
        if (mSensorManager != null) {
            mSensorAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (mSensorAccelerometer != null) {
                mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
            }
            mSensorMagnetic = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (mSensorMagnetic != null) {
                mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    /**
     * 开启地图的定位图层。
     * 原实现依赖百度定位 SDK，这里改为系统 LocationManager + osmdroid 的定位图层，
     * 显示的同样是系统位置（也就是 ServiceGo 注入的模拟位置）。
     */
    private void initMapLocation() {
        try {
            // 定位属于运行时权限：WelcomeActivity 已申请，这里再显式检查一次，
            // 既避免权限被撤销后抛 SecurityException，也满足 lint 的 MissingPermission 检查。
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                XLog.e("ERROR: location permission is not granted");
                return;
            }

            // 定位图层
            mMyLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), mMapView);
            mMyLocationOverlay.enableMyLocation();
            mMapView.getOverlays().add(mMyLocationOverlay);

            // 系统定位，用于首次进入时把地图移动到当前位置
            mSysLocManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (mSysLocManager == null) {
                return;
            }

            mSysLocListener = new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    if (location == null || mMapView == null) {// mapview 销毁后不在处理新接收的位置
                        return;
                    }

                    mCurrentLat = location.getLatitude();
                    mCurrentLon = location.getLongitude();

                    if (isFirstLoc) {
                        isFirstLoc = false;
                        // 内部依旧保存 BD-09 坐标
                        mMarkLatLngMap = wgs84ToBd09(mCurrentLon, mCurrentLat);
                        mMapView.getController().setZoom(18.0);
                        mMapView.getController().animateTo(new GeoPoint(mCurrentLat, mCurrentLon));

                        XLog.i("First WGS84 LatLng: " + mCurrentLat + "," + mCurrentLon);
                    }
                }

                @Override
                public void onProviderEnabled(@NonNull String provider) {
                }

                @Override
                public void onProviderDisabled(@NonNull String provider) {
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }
            };

            for (String provider : mSysLocManager.getProviders(true)) {
                try {
                    mSysLocManager.requestLocationUpdates(provider, 1000L, 0.0f,
                            mSysLocListener, Looper.getMainLooper());
                } catch (Exception e) {
                    XLog.e("ERROR: requestLocationUpdates - " + provider);
                }
            }

            // 先用最后一次已知位置把地图定位过去，避免等待首次回调
            Location lastLocation = null;
            try {
                lastLocation = mSysLocManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (lastLocation == null) {
                    lastLocation = mSysLocManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
            } catch (Exception e) {
                XLog.e("ERROR: getLastKnownLocation");
            }
            if (lastLocation != null) {
                mSysLocListener.onLocationChanged(lastLocation);
            }
        } catch (Exception e) {
            XLog.e("ERROR: initMapLocation");
        }
    }

    //地图上各按键的监听
    private void initMapButton() {
        RadioGroup mGroupMapType = this.findViewById(R.id.RadioGroupMapType);
        mGroupMapType.setOnCheckedChangeListener((group, checkedId) -> {
            if (mMapView == null) {
                return;
            }

            if (checkedId == R.id.mapNormal) {
                // OpenStreetMap 标准地图
                mMapView.setTileSource(TileSourceUtils.OSM_STANDARD);
            }

            if (checkedId == R.id.mapSatellite) {
                // Esri World Imagery 卫星影像（瓦片顺序 Z/Y/X）
                mMapView.setTileSource(TileSourceUtils.ESRI_WORLD_IMAGERY);
            }

            mMapView.invalidate();
        });

        ImageButton curPosBtn = this.findViewById(R.id.cur_position);
        curPosBtn.setOnClickListener(v -> resetMap());

        ImageButton zoomInBtn = this.findViewById(R.id.zoom_in);
        zoomInBtn.setOnClickListener(v -> {
            if (mMapView != null) {
                mMapView.getController().zoomIn();
            }
        });

        ImageButton zoomOutBtn = this.findViewById(R.id.zoom_out);
        zoomOutBtn.setOnClickListener(v -> {
            if (mMapView != null) {
                mMapView.getController().zoomOut();
            }
        });

        ImageButton jumpPosBtn = this.findViewById(R.id.jump_pos);
        jumpPosBtn.setOnClickListener(v -> showJumpDialog());

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> showInputPositionDialog());
    }

    /**
     * 手动输入经纬度定位。
     *
     * <p>原实现把“经度”“纬度”拆成两个输入框，但布局里只有第一个框有 id、第二个框拿不到，
     * 也没有提供粘贴整串坐标的入口，实际用起来经常出现“输入了但没定位”。现在改为
     * 单个输入框 + 自动识别（{@link MapUtils#parseLngLat}），并保留坐标系选择。</p>
     */
    private void showInputPositionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.input_button);
        View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
        builder.setView(view);

        final AlertDialog dialog = builder.show();

        EditText input = view.findViewById(R.id.input_position_text);
        RadioButton rbGps = view.findViewById(R.id.pos_type_gps);
        Button btnPaste = view.findViewById(R.id.input_position_paste);

        // 剪贴板里如果有经纬度，直接填好，省去手动粘贴
        String clipboard = getClipboardText();
        if (MapUtils.parseLngLat(clipboard) != null) {
            input.setText(clipboard);
            input.setSelection(input.getText().length());
        }

        btnPaste.setOnClickListener(v -> {
            String text = getClipboardText();
            if (TextUtils.isEmpty(text)) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.jump_position_clip_empty));
                return;
            }
            input.setText(text);
            input.setSelection(input.getText().length());
        });

        Button btnGo = view.findViewById(R.id.input_position_ok);
        btnGo.setOnClickListener(v2 -> {
            String text = input.getText().toString().trim();
            if (TextUtils.isEmpty(text)) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.input_position_empty));
                return;
            }

            double[] lngLat = MapUtils.parseLngLat(text);
            if (lngLat == null) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_error_input));
                return;
            }

            if (rbGps.isChecked()) {
                // GPS 坐标系（WGS-84）：内部仍按 BD-09 保存，绘制时再转回来
                double[] bd09 = MapUtils.wgs2bd09(lngLat[0], lngLat[1]);
                mMarkLatLngMap = new GeoPoint(bd09[1], bd09[0]);
            } else {
                mMarkLatLngMap = new GeoPoint(lngLat[1], lngLat[0]);
            }

            mMarkName = getResources().getString(R.string.input_button);
            markMap();
            mMapView.getController().setZoom(18.0);
            mMapView.getController().setCenter(
                    bd09ToWgs84(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude()));

            dialog.dismiss();
            GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.input_position_ok_toast));
        });

        Button btnCancel = view.findViewById(R.id.input_position_cancel);
        btnCancel.setOnClickListener(v1 -> dialog.dismiss());
    }

    /*============================== 跳转经纬度 / 地址 ==============================*/

    /**
     * 跳转位置：粘贴（或输入）经纬度、地址，直接在地图上选中并跳过去。
     *
     * <p>输入的是两个数值时按经纬度处理（默认“经度,纬度”，只有一个数值超过 90 度时自动识别）；
     * 其余内容当作地址，交给 OpenStreetMap 检索后选中第一条结果。</p>
     */
    private void showJumpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(getResources().getString(R.string.jump_position_title));
        View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_jump, null);
        builder.setView(view);
        final AlertDialog dialog = builder.show();

        final EditText input = view.findViewById(R.id.jump_position_input);
        final RadioButton rbBD = view.findViewById(R.id.jump_type_bd);
        Button btnPaste = view.findViewById(R.id.jump_position_paste);

        // 剪贴板里如果是经纬度就直接填好，省去手动粘贴
        String clipboard = getClipboardText();
        if (clipboard != null && MapUtils.parseLngLat(clipboard) != null) {
            input.setText(clipboard);
            input.setSelection(input.getText().length());
        }

        btnPaste.setOnClickListener(v -> {
            String text = getClipboardText();
            if (TextUtils.isEmpty(text)) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.jump_position_clip_empty));
                return;
            }
            input.setText(text);
            input.setSelection(input.getText().length());
        });

        Button btnJump = view.findViewById(R.id.jump_position_ok);
        btnJump.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (TextUtils.isEmpty(text)) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.jump_position_empty));
                return;
            }
            dialog.dismiss();
            doJump(text, rbBD.isChecked());
        });

        Button btnCancel = view.findViewById(R.id.jump_position_cancel);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    /** 读取剪贴板文本，失败或为空时返回 null */
    private String getClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }

            ClipData clipData = cm.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() <= 0) {
                return null;
            }

            CharSequence text = clipData.getItemAt(0).coerceToText(this);
            return text == null ? null : text.toString().trim();
        } catch (Exception e) {
            XLog.e("ERROR: getClipboardText");
            return null;
        }
    }

    /**
     * 执行跳转：能解析成经纬度就按坐标跳，否则按地址检索
     *
     * @param isBd09 经纬度输入是否为 BD-09 坐标系
     */
    private void doJump(String text, boolean isBd09) {
        double[] lngLat = MapUtils.parseLngLat(text);

        if (lngLat != null) {
            if (isBd09) {
                jumpToBd09(lngLat[0], lngLat[1], text);
            } else {
                double[] bd09 = MapUtils.wgs2bd09(lngLat[0], lngLat[1]);
                jumpToBd09(bd09[0], bd09[1], text);
            }
            GoUtils.DisplayToast(this, getResources().getString(R.string.jump_position_done));
            return;
        }

        if (mGeoCoder == null) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.jump_position_fail));
            return;
        }

        // 不是经纬度就当成地址去检索
        GoUtils.DisplayToast(this, getResources().getString(R.string.jump_position_searching));
        mGeoCoder.search(text, pois -> {
            if (pois == null || pois.isEmpty()) {
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.app_search_null));
                return;
            }

            OsmGeocoder.Poi poi = pois.get(0);
            GeoPoint bd09 = wgs84ToBd09(poi.longitude, poi.latitude);
            jumpToBd09(bd09.getLongitude(), bd09.getLatitude(), poi.name);

            String name = TextUtils.isEmpty(poi.name) ? poi.address : poi.name;
            GoUtils.DisplayToast(MainActivity.this,
                    getResources().getString(R.string.jump_position_result) + name);
        });
    }

    /** 把 BD-09 坐标选中并把地图视野移过去（只选点，不传送） */
    private void jumpToBd09(double bd09Lng, double bd09Lat, String name) {
        try {
            if (mMapView == null) {
                return;
            }

            mMarkName = TextUtils.isEmpty(name)
                    ? getResources().getString(R.string.jump_position_default_name) : name;
            mMarkLatLngMap = new GeoPoint(bd09Lat, bd09Lng);
            markMap();
            mMapView.getController().setZoom(18.0);
            mMapView.getController().animateTo(bd09ToWgs84(bd09Lng, bd09Lat));
        } catch (Exception e) {
            XLog.e("ERROR: jumpToBd09");
            GoUtils.DisplayToast(this, getResources().getString(R.string.jump_position_fail));
        }
    }

    //标定选择的位置
    private void markMap() {
        markMapOnView(mMapView, mMarkMarker, mMarkLatLngMap);
    }

    /**
     * 把 BD-09 的选点绘制到地图上（绘制前转换成 WGS-84）
     */
    private static void markMapOnView(MapView mapView, Marker marker, GeoPoint bd09Point) {
        if (mapView == null || marker == null || bd09Point == null) {
            return;
        }
        marker.closeInfoWindow();
        marker.setPosition(bd09ToWgs84(bd09Point.getLongitude(), bd09Point.getLatitude()));
        if (!mapView.getOverlays().contains(marker)) {
            mapView.getOverlays().add(marker);
        }
        mapView.invalidate();
    }

    /**
     * 清除选点标记
     */
    private static void clearMark(MapView mapView, Marker marker) {
        if (mapView == null) {
            return;
        }
        if (marker != null) {
            marker.closeInfoWindow();
            mapView.getOverlays().remove(marker);
        }
        mapView.invalidate();
    }

    /**
     * 创建选点标记。osmdroid 的 Marker 需要各自持有一份 Drawable，
     * 所以这里从 ConstantState 新建，避免与摇杆悬浮窗地图共用同一个实例。
     */
    private Marker createMarkMarker() {
        if (mMapView == null || sMapIndicatorDrawable == null) {
            return null;
        }
        Drawable icon = sMapIndicatorDrawable;
        if (sMapIndicatorDrawable.getConstantState() != null) {
            icon = sMapIndicatorDrawable.getConstantState().newDrawable();
        }
        Marker marker = new Marker(mMapView);
        marker.setIcon(icon);
        // 图标底部中点对准坐标点，与原百度地图标记的视觉一致
        marker.setAnchor(0.5f, 1.0f);
        return marker;
    }

    /**
     * BD-09 -> WGS-84
     *
     * @return WGS-84 坐标点，可直接交给 osmdroid 绘制
     */
    private static GeoPoint bd09ToWgs84(double bd09Lng, double bd09Lat) {
        double[] wgs84 = MapUtils.bd2wgs(bd09Lng, bd09Lat);
        return new GeoPoint(wgs84[1], wgs84[0]);
    }

    /**
     * WGS-84 -> BD-09
     *
     * @return BD-09 坐标点，用于保持工程内部原有的坐标语义
     */
    private static GeoPoint wgs84ToBd09(double wgs84Lng, double wgs84Lat) {
        double[] bd09 = MapUtils.wgs2bd09(wgs84Lng, wgs84Lat);
        return new GeoPoint(bd09[1], bd09[0]);
    }

    private void resetMap() {
        clearMark(mMapView, mMarkMarker);
        mMarkLatLngMap = null;

        // 定位图层由 mMyLocationOverlay 负责刷新，这里只需要把视野移回当前位置
        if (mMapView != null && (mCurrentLat != 0.0 || mCurrentLon != 0.0)) {
            mMapView.getController().setZoom(18.0);
            mMapView.getController().animateTo(new GeoPoint(mCurrentLat, mCurrentLon));
        }
    }

    // 在地图上显示位置
    public static boolean showLocation(String name, String bd09Longitude, String bd09Latitude) {
        boolean ret = true;

        try {
            if (!bd09Longitude.isEmpty() && !bd09Latitude.isEmpty()) {
                mMarkName = name;
                mMarkLatLngMap = new GeoPoint(Double.parseDouble(bd09Latitude), Double.parseDouble(bd09Longitude));
                markMapOnView(mMapView, mMarkMarker, mMarkLatLngMap);
                mMapView.getController().setCenter(
                        bd09ToWgs84(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude()));
            }
        } catch (Exception e) {
            ret = false;
            XLog.e("ERROR: showHistoryLocation");
        }

        return ret;
    }

    /**
     * 主界面地图上的 POI 信息气泡，对应原实现里的百度 InfoWindow。
     * 布局与按钮逻辑完全复用 location_poi_info。
     */
    private static class PoiInfoWindow extends InfoWindow {
        PoiInfoWindow(View view, MapView mapView) {
            super(view, mapView);
        }

        @Override
        public void onOpen(Object item) {
        }

        @Override
        public void onClose() {
        }
    }

    private void initGoBtn() {
        mButtonStart = findViewById(R.id.faBtnStart);
        mButtonStart.setOnClickListener(this::doGoLocation);

        mButtonScript = findViewById(R.id.faBtnScript);
        if (mButtonScript != null) {
            mButtonScript.setOnClickListener(this::doScriptLocation);
        }
    }

    /** 脚本播放状态回调（可能在定位线程触发，刷新界面要切回主线程） */
    private final ServiceGo.ScriptListener mScriptListener = new ServiceGo.ScriptListener() {
        @Override
        public void onScriptSegment(com.zcshou.script.ScriptRoute route, int index,
                                    com.zcshou.script.ScriptWaypoint.Mode mode, double speed) {
            mRunningScriptId = route == null ? null : route.id;
            runOnUiThread(MainActivity.this::updateScriptButton);
        }

        @Override
        public void onScriptFinish(com.zcshou.script.ScriptRoute route) {
            mRunningScriptId = route == null ? null : route.id;
            runOnUiThread(() -> {
                updateScriptButton();
                GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.script_finished));
            });
        }

        @Override
        public void onScriptStopped() {
            mRunningScriptId = null;
            runOnUiThread(MainActivity.this::updateScriptButton);
        }
    };

    /** 按当前是否在播放脚本切换入口按钮的图标（路线操作条上的按钮随之刷新） */
    private void updateScriptButton() {
        if (mButtonScript != null) {
            boolean running = mRunningScriptId != null;
            mButtonScript.setImageResource(running ? R.drawable.ic_close : R.drawable.ic_script);
        }

        updateRouteUi();
    }

    /**
     * 脚本模式入口：直接打开脚本列表。
     *
     * <p>上一次模拟的位置已经由 ServiceGo 保存在 SharedPreferences 里，脚本编辑页可以直接引用，
     * 因此这里不需要绑定服务。</p>
     */
    private void doScriptLocation(View v) {
        // 已经在播放脚本时，这个按钮变成“停止脚本”
        if (mRunningScriptId != null && mServiceBinder != null) {
            boolean finished = mServiceBinder.getScriptState() == ServiceGo.SCRIPT_STATE_FINISHED;
            try {
                mServiceBinder.stopScript();
                mRunningScriptId = null;
                updateScriptButton();
                Snackbar.make(v, getResources().getString(
                                finished ? R.string.script_finished : R.string.script_stopped),
                        Snackbar.LENGTH_LONG).setAction("Action", null).show();

                // 单次脚本已经跑完，顺手把服务停掉，避免前台通知一直挂着
                // 注意：stopService 只有在没有绑定时才会真正销毁服务，所以这里要先解绑
                if (finished) {
                    unbindServiceIfNeeded();
                    stopService(new Intent(MainActivity.this, ServiceGo.class));
                    isMockServStart = false;
                }
                return;
            } catch (Exception e) {
                XLog.e("ERROR: stopScript", e);
            }
        }

        startActivity(new Intent(MainActivity.this, ScriptActivity.class));
    }

    /*============================== 主界面连续选点成路线（连贯移动） ==============================*/

    /** 路线选点入口按钮、操作条与连线图层 */
    private void initRoutePicking() {
        mButtonRoute = findViewById(R.id.faBtnRoute);
        if (mButtonRoute != null) {
            mButtonRoute.setOnClickListener(v -> setRoutePicking(!mRoutePicking));
        }

        mRouteBar = findViewById(R.id.route_bar);
        mRouteHint = findViewById(R.id.route_hint);
        mRouteGoButton = findViewById(R.id.route_go);

        mRouteModeSpinner = findViewById(R.id.route_mode);
        if (mRouteModeSpinner != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                    new String[] {
                            getResources().getString(R.string.script_mode_walk),
                            getResources().getString(R.string.script_mode_run),
                            getResources().getString(R.string.script_mode_bike)});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mRouteModeSpinner.setAdapter(adapter);
            mRouteModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    mRouteMode = position == 1 ? ScriptWaypoint.Mode.RUN
                            : position == 2 ? ScriptWaypoint.Mode.BIKE : ScriptWaypoint.Mode.WALK;
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }

        View undoButton = findViewById(R.id.route_undo);
        if (undoButton != null) {
            undoButton.setOnClickListener(v -> undoRoutePoint());
        }
        View clearButton = findViewById(R.id.route_clear);
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> clearRoutePoints());
        }
        View closeButton = findViewById(R.id.route_close);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> setRoutePicking(false));
        }
        if (mRouteGoButton != null) {
            mRouteGoButton.setOnClickListener(v -> {
                if (isMapRoutePlaying()) {
                    stopMapRoute();
                } else {
                    startMapRoute();
                }
            });
        }

        // 连线图层：先放进地图，选点过程中不断更新坐标列表
        if (mMapView != null) {
            mRouteLine = new Polyline(mMapView);
            mRouteLine.getOutlinePaint().setColor(ContextCompat.getColor(this, R.color.colorAccent));
            mRouteLine.getOutlinePaint().setStrokeWidth(8.0f);
            addRouteOverlay(mRouteLine);
        }

        updateRouteUi();
    }

    /**
     * 加入路线图层（连线 / 图钉）。
     *
     * <p>osmdroid 的事件按图层反序派发：这些图层只用来“看”，如果排在
     * {@link MapEventsOverlay} 之后，点在已有图钉或连线上就触达不到地图回调，
     * 也就没法继续加路点。所以每次加完都把点击派发器挪回最上面。</p>
     */
    private void addRouteOverlay(Overlay overlay) {
        if (mMapView == null || overlay == null) {
            return;
        }

        List<Overlay> overlays = mMapView.getOverlays();
        overlays.add(overlay);
        if (mMapEventsOverlay != null && overlays.remove(mMapEventsOverlay)) {
            overlays.add(mMapEventsOverlay);
        }
    }

    /** 开关“路线选点”模式 */
    private void setRoutePicking(boolean picking) {
        mRoutePicking = picking;
        if (mRouteBar != null) {
            mRouteBar.setVisibility(picking ? View.VISIBLE : View.GONE);
        }
        if (mButtonRoute != null) {
            mButtonRoute.setImageResource(picking ? R.drawable.ic_close : R.drawable.ic_route);
        }

        GoUtils.DisplayToast(this, getResources().getString(
                picking ? R.string.route_mode_on : R.string.route_mode_off));
        updateRouteUi();
    }

    /**
     * 往路线末尾追加一个路点。
     *
     * @param lngWgs84 经度（WGS-84：地图回调给的就是这个坐标系，也正是 ServiceGo 注入系统的坐标系）
     * @param latWgs84 纬度（WGS-84）
     */
    private void addRoutePoint(double lngWgs84, double latWgs84) {
        mRoutePoints.add(new ScriptWaypoint(lngWgs84, latWgs84, getSettingAltitude(),
                mRouteMode, getModeSpeed(mRouteMode), 0));
        redrawRoute();
    }

    /** 撤销最后一个路点 */
    private void undoRoutePoint() {
        if (mRoutePoints.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_empty));
            return;
        }

        mRoutePoints.remove(mRoutePoints.size() - 1);
        redrawRoute();
    }

    /** 清空所有路点（连同地图上的连线与图钉） */
    private void clearRoutePoints() {
        mRoutePoints.clear();
        redrawRoute();
    }

    /** 重画路线：连线 + 每个路点一个图钉 */
    private void redrawRoute() {
        if (mMapView == null) {
            return;
        }

        for (Marker marker : mRouteMarkers) {
            marker.closeInfoWindow();
            mMapView.getOverlays().remove(marker);
        }
        mRouteMarkers.clear();

        List<GeoPoint> points = new ArrayList<>();
        for (ScriptWaypoint point : mRoutePoints) {
            GeoPoint geoPoint = new GeoPoint(point.lat, point.lng);
            points.add(geoPoint);

            Marker marker = createMarkMarker();
            if (marker != null) {
                marker.setPosition(geoPoint);
                marker.setTitle(getResources().getString(R.string.route_point_title, mRouteMarkers.size() + 1));
                addRouteOverlay(marker);
                mRouteMarkers.add(marker);
            }
        }

        if (mRouteLine != null) {
            mRouteLine.setPoints(points);
        }

        mMapView.invalidate();
        updateRouteUi();
    }

    /** 刷新操作条：已选点数 + 主按钮是“开始移动”还是“停止移动” */
    private void updateRouteUi() {
        if (mRouteHint != null) {
            int count = mRoutePoints.size();
            mRouteHint.setText(count == 0
                    ? getResources().getString(R.string.route_hint_empty)
                    : getResources().getString(R.string.route_hint_count, count));
        }

        if (mRouteGoButton != null) {
            mRouteGoButton.setText(isMapRoutePlaying() ? R.string.route_stop : R.string.route_go);
        }
    }

    /** 当前正在跑的路线是不是主界面选的这条 */
    private boolean isMapRoutePlaying() {
        return MAP_ROUTE_ID.equals(mRunningScriptId);
    }

    /** 把当前选点存成一条脚本路线，并让 ServiceGo 沿路线平滑移动 */
    private void startMapRoute() {
        if (mRoutePoints.size() < 2) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_need_two));
            return;
        }

        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }
        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }
        if (!Settings.canDrawOverlays(getApplicationContext())) {
            GoUtils.showEnableFloatWindowDialog(this);
            return;
        }
        if (!GoUtils.isAllowMockLocation(this)) {
            GoUtils.showEnableMockLocationDialog(this);
            return;
        }

        ScriptRoute route = buildMapRoute();
        new ScriptStore(this).save(route);

        ScriptWaypoint first = route.points.get(0);
        Intent intent = new Intent(MainActivity.this, ServiceGo.class);
        intent.putExtra(SCRIPT_ROUTE_ID, route.id);
        intent.putExtra(LNG_MSG_ID, first.lng);
        intent.putExtra(LAT_MSG_ID, first.lat);
        intent.putExtra(ALT_MSG_ID, first.alt);

        try {
            bindServiceIfNeeded();
            startForegroundService(intent);
            mRunningScriptId = route.id;
            updateScriptButton();
            GoUtils.DisplayToast(this, getResources().getString(R.string.route_started,
                    route.points.size(), ScriptParser.formatDistance(ScriptParser.totalDistance(route))));
        } catch (Exception e) {
            XLog.e("ERROR: startMapRoute", e);
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_service));
        }
    }

    /** 停止沿路线移动（当前位置保持不变） */
    private void stopMapRoute() {
        if (mServiceBinder != null) {
            try {
                mServiceBinder.stopScript();
            } catch (Exception e) {
                XLog.e("ERROR: stopMapRoute", e);
            }
        } else {
            try {
                stopService(new Intent(MainActivity.this, ServiceGo.class));
            } catch (Exception e) {
                XLog.e("ERROR: stopMapRoute");
            }
        }

        mRunningScriptId = null;
        updateScriptButton();
        GoUtils.DisplayToast(this, getResources().getString(R.string.script_stopped));
    }

    /**
     * 把主界面选的点整理成一条脚本路线。
     *
     * <p>同时按脚本格式生成文本：一是让 ServiceGo 重新解析文本后依旧得到这些 WGS-84 坐标
     * （文本按“脚本模式记住的坐标系”输出，见 {@link ScriptParser#KEY_SCRIPT_FROM_BD09}），
     * 二是让这条路线在脚本模式里能直接看到和编辑。</p>
     */
    private ScriptRoute buildMapRoute() {
        ScriptRoute route = new ScriptRoute();
        route.id = MAP_ROUTE_ID;
        route.name = getResources().getString(R.string.route_default_name);
        route.endMode = ScriptRoute.END_STOP;
        route.points = new ArrayList<>(mRoutePoints);

        // 这条路线由主界面选点生成，坐标本身就是 WGS-84，并把坐标系记在脚本自己身上，
        // 之后不管全局单选怎么变，重新解析 route.text 都不会偏移
        boolean fromBd09 = false;
        route.fromBd09 = Boolean.FALSE;
        StringBuilder text = new StringBuilder();
        for (ScriptWaypoint point : route.points) {
            text.append(ScriptParser.formatPoint(point, fromBd09)).append('\n');
        }
        route.text = text.toString();

        return route;
    }

    /** “设置”里某个状态对应的速度（米/秒） */
    private double getModeSpeed(ScriptWaypoint.Mode mode) {
        double[] speeds = getModeSpeeds();
        return speeds[Math.max(0, Math.min(speeds.length - 1, mode.ordinal()))];
    }

    /** “设置”里的步行 / 跑步 / 骑行速度，顺序与 {@link ScriptWaypoint.Mode} 一致 */
    private double[] getModeSpeeds() {
        return ScriptParser.modeSpeeds(
                getDoubleSetting("setting_walk", 1.2),
                getDoubleSetting("setting_run", 3.6),
                getDoubleSetting("setting_bike", 10.0));
    }

    /** 读取浮点型设置项，非法输入时退回默认值 */
    private double getDoubleSetting(String key, double defaultValue) {
        try {
            return Double.parseDouble(sharedPreferences.getString(key, Double.toString(defaultValue)));
        } catch (Exception e) {
            XLog.e("ERROR: invalid setting - " + key);
            return defaultValue;
        }
    }

    /** 读取设置里的海拔高度，非法输入时退回默认值，避免 NumberFormatException 闪退 */
    private double getSettingAltitude() {
        try {
            return Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
        } catch (Exception e) {
            XLog.e("ERROR: setting_altitude is not a number");
            return 55.0;
        }
    }

    private void bindServiceIfNeeded() {
        if (mBoundToService) {
            return;
        }

        if (bindService(new Intent(MainActivity.this, ServiceGo.class), mConnection, BIND_AUTO_CREATE)) {
            mBoundToService = true;
        }
    }

    /** 统一解绑，避免退出时因为“服务不是本界面启动的”而漏掉解绑 */
    private void unbindServiceIfNeeded() {
        if (!mBoundToService && !isMockServStart) {
            return;
        }

        try {
            if (mServiceBinder != null) {
                mServiceBinder.clearScriptListener();
            }
            unbindService(mConnection);
        } catch (Exception e) {
            XLog.e("ERROR: unbindService");
        }
        mBoundToService = false;
        mServiceBinder = null;
    }

    private void startGoLocation() {
        if (mMarkLatLngMap == null) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_location));
            return;
        }

        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
        serviceGoIntent.putExtra(LNG_MSG_ID, latLng[0]);
        serviceGoIntent.putExtra(LAT_MSG_ID, latLng[1]);
        serviceGoIntent.putExtra(ALT_MSG_ID, getSettingAltitude());

        try {
            bindService(serviceGoIntent, mConnection, BIND_AUTO_CREATE);    // 绑定服务和活动，之后活动就可以去调服务的方法了
            mBoundToService = true;
            startForegroundService(serviceGoIntent);
            XLog.d("startForegroundService: ServiceGo");

            isMockServStart = true;
        } catch (Exception e) {
            XLog.e("ERROR: startGoLocation", e);
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_service));
        }
    }

    private void stopGoLocation() {
        unbindServiceIfNeeded();
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        stopService(serviceGoIntent);
        isMockServStart = false;
    }

    private void doGoLocation(View v) {
        if (!GoUtils.isNetworkAvailable(this)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_network));
            return;
        }

        if (!GoUtils.isGpsOpened(this)) {
            GoUtils.showEnableGpsDialog(this);
            return;
        }

        if (!Settings.canDrawOverlays(getApplicationContext())) {//悬浮窗权限判断
            GoUtils.showEnableFloatWindowDialog(this);
            XLog.e("无悬浮窗权限!");
            return;
        }

        if (isMockServStart) {
            if (mMarkLatLngMap == null) {
                stopGoLocation();
                Snackbar.make(v, "模拟位置已终止", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                mButtonStart.setImageResource(R.drawable.ic_position);
            } else {
                if (mServiceBinder == null) {
                    // 服务还没连接上（例如进程刚被系统重启），重新绑定，避免空指针闪退
                    bindServiceIfNeeded();
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_service));
                    return;
                }

                double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
                double alt = getSettingAltitude();
                mServiceBinder.setPosition(latLng[0], latLng[1], alt);
                Snackbar.make(v, "已传送到新位置", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();

                recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());

                clearMark(mMapView, mMarkMarker);
                mMarkLatLngMap = null;

                if (GoUtils.isWifiEnabled(MainActivity.this)) {
                    GoUtils.showDisableWifiDialog(MainActivity.this);
                }
            }
        } else {
            if (!GoUtils.isAllowMockLocation(this)) {
                GoUtils.showEnableMockLocationDialog(this);
                XLog.e("无模拟位置权限!");
            } else {
                if (mMarkLatLngMap == null) {
                    Snackbar.make(v, "请先点击地图位置或者搜索位置", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();
                } else {
                    startGoLocation();
                    mButtonStart.setImageResource(R.drawable.ic_fly);
                    Snackbar.make(v, "模拟位置已启动", Snackbar.LENGTH_LONG)
                            .setAction("Action", null).show();

                    recordCurrentLocation(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
                    clearMark(mMapView, mMarkMarker);
                    mMarkLatLngMap = null;

                    if (GoUtils.isWifiEnabled(MainActivity.this)) {
                        GoUtils.showDisableWifiDialog(MainActivity.this);
                    }
                }
            }
        }
    }

    /*============================== 历史记录 相关 ==============================*/
    private void initStoreHistory() {
        try {
            // 定位历史
            DataBaseHistoryLocation dbLocation = new DataBaseHistoryLocation(getApplicationContext());
            mLocationHistoryDB = dbLocation.getWritableDatabase();
            // 搜索历史
            DataBaseHistorySearch dbHistory = new DataBaseHistorySearch(getApplicationContext());
            mSearchHistoryDB = dbHistory.getWritableDatabase();
        } catch (Exception e) {
            XLog.e("ERROR: sqlite init error");
        }
    }

    //获取查询历史
    private List<Map<String, Object>> getSearchHistory() {
        List<Map<String, Object>> data = new ArrayList<>();

        try {
            Cursor cursor = mSearchHistoryDB.query(DataBaseHistorySearch.TABLE_NAME, null,
                    DataBaseHistorySearch.DB_COLUMN_ID + " > ?", new String[] {"0"},
                    null, null, DataBaseHistorySearch.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> searchHistoryItem = new HashMap<>();
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_KEY, cursor.getString(1));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, cursor.getString(2));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, "" + cursor.getInt(3));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, "" + cursor.getInt(4));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, cursor.getString(7));
                searchHistoryItem.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, cursor.getString(8));
                data.add(searchHistoryItem);
            }
            cursor.close();
        } catch (Exception e) {
            XLog.e("ERROR: getSearchHistory");
        }

        return data;
    }

    // 记录请求的位置信息
    private void recordCurrentLocation(double lng, double lat) {
        //参数坐标系：bd09
        final double[] latLng = MapUtils.bd2wgs(lng, lat);
        // 逆地理编码改为 OpenStreetMap Nominatim，传入 WGS-84 坐标
        mGeoCoder.reverse(latLng[1], latLng[0], poi -> {
            try {
                String address = (poi == null || poi.address.isEmpty()) ? null : poi.address;
                if (address == null) {
                    address = mMarkName != null ? mMarkName
                            : getResources().getString(R.string.history_location_default_name);
                }

                //插表参数
                ContentValues contentValues = new ContentValues();
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LOCATION, address);
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LONGITUDE_CUSTOM, Double.toString(lng));
                contentValues.put(DataBaseHistoryLocation.DB_COLUMN_LATITUDE_CUSTOM, Double.toString(lat));

                DataBaseHistoryLocation.saveHistoryLocation(mLocationHistoryDB, contentValues);
            } catch (Exception e) {
                XLog.e("ERROR: recordCurrentLocation");
            }
        });
    }

    /*============================== SearchView 相关 ==============================*/
    private void initSearchView() {
        mSearchLayout = findViewById(R.id.search_linear);
        mHistoryLayout = findViewById(R.id.search_history_linear);

        mSearchList = findViewById(R.id.search_list_view);
        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            mMarkName = ((TextView) view.findViewById(R.id.poi_name)).getText().toString();
            mMarkLatLngMap = new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lng));
            mMapView.getController().setCenter(
                    bd09ToWgs84(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude()));

            markMap();

            double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());

            // mSearchList.setVisibility(View.GONE);
            //搜索历史 插表参数
            ContentValues contentValues = new ContentValues();
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, mMarkName);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, ((TextView) view.findViewById(R.id.poi_address)).getText().toString());
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
            contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

            DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            mSearchLayout.setVisibility(View.INVISIBLE);
            searchItem.collapseActionView();
        });
        //搜索历史列表的点击监听
        mSearchHistoryList = findViewById(R.id.search_history_list_view);
        mSearchHistoryList.setOnItemClickListener((parent, view, position, id) -> {
            String searchDescription = ((TextView) view.findViewById(R.id.search_description)).getText().toString();
            String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();
            String searchIsLoc = ((TextView) view.findViewById(R.id.search_isLoc)).getText().toString();

            //如果是定位搜索
            if (searchIsLoc.equals("1")) {
                String lng = ((TextView) view.findViewById(R.id.search_longitude)).getText().toString();
                String lat = ((TextView) view.findViewById(R.id.search_latitude)).getText().toString();
                // mMarkName = ((TextView) view.findViewById(R.id.poi_name)).getText().toString();
                mMarkLatLngMap = new GeoPoint(Double.parseDouble(lat), Double.parseDouble(lng));
                mMapView.getController().setCenter(
                        bd09ToWgs84(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude()));

                markMap();

                double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());

                //设置列表不可见
                mHistoryLayout.setVisibility(View.INVISIBLE);
                searchItem.collapseActionView();
                //更新表
                ContentValues contentValues = new ContentValues();
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_KEY, searchKey);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_DESCRIPTION, searchDescription);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_IS_LOCATION, DataBaseHistorySearch.DB_SEARCH_TYPE_RESULT);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM, lng);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM, lat);
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LONGITUDE_WGS84, String.valueOf(latLng[0]));
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_LATITUDE_WGS84, String.valueOf(latLng[1]));
                contentValues.put(DataBaseHistorySearch.DB_COLUMN_TIMESTAMP, System.currentTimeMillis() / 1000);

                DataBaseHistorySearch.saveHistorySearch(mSearchHistoryDB, contentValues);
            } else if (searchIsLoc.equals("0")) { //如果仅仅是搜索
                try {
                    searchView.setQuery(searchKey, true);
                } catch (Exception e) {
                    GoUtils.DisplayToast(this, getResources().getString(R.string.app_error_search));
                    XLog.e(getResources().getString(R.string.app_error_search));
                }
            } else {
                XLog.e(getResources().getString(R.string.app_error_param));
            }
        });
        mSearchHistoryList.setOnItemLongClickListener((parent, view, position, id) -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("警告")//这里是表头的内容
                    .setMessage("确定要删除该项搜索记录吗?")//这里是中间显示的具体信息
                    .setPositiveButton("确定",(dialog, which) -> {
                        String searchKey = ((TextView) view.findViewById(R.id.search_key)).getText().toString();

                        try {
                            mSearchHistoryDB.delete(DataBaseHistorySearch.TABLE_NAME, DataBaseHistorySearch.DB_COLUMN_KEY + " = ?", new String[] {searchKey});
                            //删除成功
                            //展示搜索历史
                            List<Map<String, Object>> data = getSearchHistory();

                            if (!data.isEmpty()) {
                                SimpleAdapter simAdapt = new SimpleAdapter(
                                        MainActivity.this,
                                        data,
                                        R.layout.search_item,
                                        new String[] {DataBaseHistorySearch.DB_COLUMN_KEY,
                                                DataBaseHistorySearch.DB_COLUMN_DESCRIPTION,
                                                DataBaseHistorySearch.DB_COLUMN_TIMESTAMP,
                                                DataBaseHistorySearch.DB_COLUMN_IS_LOCATION,
                                                DataBaseHistorySearch.DB_COLUMN_LONGITUDE_CUSTOM,
                                                DataBaseHistorySearch.DB_COLUMN_LATITUDE_CUSTOM}, // 与下面数组元素要一一对应
                                        new int[] {R.id.search_key, R.id.search_description, R.id.search_timestamp, R.id.search_isLoc, R.id.search_longitude, R.id.search_latitude});
                                mSearchHistoryList.setAdapter(simAdapt);
                                mHistoryLayout.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            XLog.e("ERROR: delete database error");
                            GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.history_delete_error));
                        }
                    })
                    .setNegativeButton("取消",
                            (dialog, which) -> {
                            })
                    .show();
            return true;
        });
    }

    /**
     * 关键字检索（OpenStreetMap Nominatim）。
     * 结果统一转换成 BD-09 后再交给列表，列表点击选点的下游逻辑因此完全不用改。
     */
    private void doSearch(String keyword) {
        mGeoCoder.search(keyword, pois -> {
            if (pois == null || pois.isEmpty()) {
                GoUtils.DisplayToast(this, getResources().getString(R.string.app_search_null));
                return;
            }

            SimpleAdapter simAdapt = new SimpleAdapter(
                    MainActivity.this,
                    getMapList(pois),
                    R.layout.search_poi_item,
                    new String[] {POI_NAME, POI_ADDRESS, POI_LONGITUDE, POI_LATITUDE}, // 与下面数组元素要一一对应
                    new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
            mSearchList.setAdapter(simAdapt);
            mSearchLayout.setVisibility(View.VISIBLE);
        });
    }

    @NonNull
    private static List<Map<String, Object>> getMapList(List<OsmGeocoder.Poi> pois) {
        List<Map<String, Object>> data = new ArrayList<>();

        for (OsmGeocoder.Poi poi : pois) {
            // 列表里的经纬度沿用原有 BD-09 语义
            double[] bd09 = MapUtils.wgs2bd09(poi.longitude, poi.latitude);

            Map<String, Object> poiItem = new HashMap<>();
            poiItem.put(POI_NAME, poi.name);
            poiItem.put(POI_ADDRESS, poi.address);
            poiItem.put(POI_LONGITUDE, "" + bd09[0]);
            poiItem.put(POI_LATITUDE, "" + bd09[1]);
            data.add(poiItem);
        }
        return data;
    }

    /*============================== 更新 相关 ==============================*/
    private void initUpdateVersion() {
        mDownloadManager =(DownloadManager) MainActivity.this.getSystemService(DOWNLOAD_SERVICE);

        // 用于监听下载完成后，转到安装界面
        mDownloadBdRcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                installNewVersion();
            }
        };
        registerReceiver(mDownloadBdRcv, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void checkUpdateVersion(boolean result) {
        String mapApiUrl = "https://api.github.com/repos/zcshou/gogogo/releases/latest";

        okhttp3.Request request = new okhttp3.Request.Builder().url(mapApiUrl).get().build();
        final Call call = mOkHttpClient.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                XLog.i("更新检测失败");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    String resp = responseBody.string();
                    // 注意，该请求在子线程，不能直接操作界面
                    runOnUiThread(() -> {
                        try {
                            JSONObject getRetJson = new JSONObject(resp);
                            String curVersion = GoUtils.getVersionName(MainActivity.this);

                            if (curVersion != null
                                    && (!getRetJson.getString("name").contains(curVersion)
                                    || !getRetJson.getString("tag_name").contains(curVersion))) {
                                final android.app.AlertDialog alertDialog = new android.app.AlertDialog.Builder(MainActivity.this).create();
                                alertDialog.show();
                                alertDialog.setCancelable(false);
                                Window window = alertDialog.getWindow();
                                if (window != null) {
                                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);      // 防止出现闪屏
                                    window.setContentView(R.layout.update);
                                    window.setGravity(Gravity.CENTER);
                                    window.setWindowAnimations(R.style.DialogAnimFadeInFadeOut);

                                    TextView updateTitle = window.findViewById(R.id.update_title);
                                    updateTitle.setText(getRetJson.getString("name"));
                                    TextView updateTime = window.findViewById(R.id.update_time);
                                    updateTime.setText(getRetJson.getString("created_at"));
                                    TextView updateCommit = window.findViewById(R.id.update_commit);
                                    updateCommit.setText(getRetJson.getString("target_commitish"));

                                    TextView updateContent = window.findViewById(R.id.update_content);
                                    final Markwon markwon = Markwon.create(MainActivity.this);
                                    markwon.setMarkdown(updateContent, getRetJson.getString("body"));

                                    Button updateCancel = window.findViewById(R.id.update_ignore);
                                    updateCancel.setOnClickListener(v -> alertDialog.cancel());

                                    /* 这里用来保存下载地址 */
                                    JSONArray jsonArray = new JSONArray(getRetJson.getString("assets"));
                                    JSONObject jsonObject = jsonArray.getJSONObject(0);
                                    String download_url = jsonObject.getString("browser_download_url");
                                    mUpdateFilename = jsonObject.getString("name");

                                    Button updateAgree = window.findViewById(R.id.update_agree);
                                    updateAgree.setOnClickListener(v -> {
                                        alertDialog.cancel();
                                        GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.update_downloading));
                                        downloadNewVersion(download_url);
                                    });
                                }
                            } else {
                                if (result) {
                                    GoUtils.DisplayToast(MainActivity.this, getResources().getString(R.string.update_last));
                                }
                            }
                        } catch (JSONException e) {
                            XLog.e("ERROR: resolve json");
                        }
                    });
                }
            }
        });
    }

    private void downloadNewVersion(String url) {
        if (mDownloadManager == null) {
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setTitle(GoUtils.getAppName(this));
        request.setDescription("正在下载新版本...");
        request.setMimeType("application/vnd.android.package-archive");

        // DownloadManager不会覆盖已有的同名文件，需要自己来删除已存在的文件
        File file = new File(getExternalFilesDir("Updates"), mUpdateFilename);
        if (file.exists()) {
            if(!file.delete()) {
                return;
            }
        }
        request.setDestinationUri(Uri.fromFile(file));

        mDownloadId = mDownloadManager.enqueue(request);
    }

    private void installNewVersion() {
        Intent install = new Intent(Intent.ACTION_VIEW);
        Uri downloadFileUri = mDownloadManager.getUriForDownloadedFile(mDownloadId);
        File file = new File(getExternalFilesDir("Updates"), mUpdateFilename);
        if (downloadFileUri != null) {
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            // 在Broadcast中启动活动需要添加Intent.FLAG_ACTIVITY_NEW_TASK
            install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);    //添加这一句表示对目标应用临时授权该Uri所代表的文件
            install.addCategory("android.intent.category.DEFAULT");
            install.setDataAndType(ShareUtils.getUriFromFile(MainActivity.this, file), "application/vnd.android.package-archive");
            startActivity(install);
        } else {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            intent.addCategory("android.intent.category.DEFAULT");
            startActivity(intent);
        }
    }
}
