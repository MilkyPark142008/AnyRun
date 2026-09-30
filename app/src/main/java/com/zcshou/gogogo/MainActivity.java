package com.zcshou.gogogo;

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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SimpleAdapter;
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
import org.osmdroid.views.overlay.InfoWindow;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
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
    private ServiceConnection mConnection;
    private FloatingActionButton mButtonStart;
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

        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mServiceBinder = (ServiceGo.ServiceGoBinder)service;
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
        mMapView.onPause();
        mSensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onResume() {
        XLog.i("MainActivity: onResume");
        mMapView.onResume();
        mSensorManager.registerListener(this, mSensorAccelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, mSensorMagnetic, SensorManager.SENSOR_DELAY_UI);
        super.onResume();
    }

    @Override
    protected void onStop() {
        XLog.i("MainActivity: onStop");
        //取消注册传感器监听
        mSensorManager.unregisterListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        XLog.i("MainActivity: onDestroy");

        if (isMockServStart) {
            unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
            Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
            stopService(serviceGoIntent);
        }
        unregisterReceiver(mDownloadBdRcv);

        mSensorManager.unregisterListener(this);

        // 退出时销毁定位
        if (mSysLocManager != null && mSysLocListener != null) {
            mSysLocManager.removeUpdates(mSysLocListener);
        }
        // 关闭定位图层
        if (mMyLocationOverlay != null) {
            mMyLocationOverlay.disableMyLocation();
        }
        mMapView.onDetach();

        //close db
        mLocationHistoryDB.close();
        mSearchHistoryDB.close();

        super.onDestroy();
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
                mMarkLatLngMap = wgs84ToBd09(p.getLongitude(), p.getLatitude());
                markMap();
                return true;
            }

            /**
             * 长按地图
             */
            @Override
            public boolean longPressHelper(GeoPoint p) {
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

        ImageButton inputPosBtn = this.findViewById(R.id.input_pos);
        inputPosBtn.setOnClickListener(v -> {
            AlertDialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("请输入经度和纬度");
            View view = LayoutInflater.from(MainActivity.this).inflate(R.layout.location_input, null);
            builder.setView(view);
            dialog = builder.show();

            EditText dialog_lng = view.findViewById(R.id.joystick_longitude);
            EditText dialog_lat = view.findViewById(R.id.joystick_latitude);
            RadioButton rbBD = view.findViewById(R.id.pos_type_bd);

            Button btnGo = view.findViewById(R.id.input_position_ok);
            btnGo.setOnClickListener(v2 -> {
                String dialog_lng_str = dialog_lng.getText().toString();
                String dialog_lat_str = dialog_lat.getText().toString();

                if (TextUtils.isEmpty(dialog_lng_str) || TextUtils.isEmpty(dialog_lat_str)) {
                    GoUtils.DisplayToast(MainActivity.this,getResources().getString(R.string.app_error_input));
                } else {
                    double dialog_lng_double = Double.parseDouble(dialog_lng_str);
                    double dialog_lat_double = Double.parseDouble(dialog_lat_str);

                    if (dialog_lng_double > 180.0 || dialog_lng_double < -180.0) {
                        GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_longitude));
                    } else {
                        if (dialog_lat_double > 90.0 || dialog_lat_double < -90.0) {
                            GoUtils.DisplayToast(MainActivity.this,  getResources().getString(R.string.app_error_latitude));
                        } else {
                            if (rbBD.isChecked()) {
                                mMarkLatLngMap = new GeoPoint(dialog_lat_double, dialog_lng_double);
                            } else {
                                //【修复】原代码把 WGS-84 的经度/纬度作为参数传反了，
                                // MapUtils.wgs2bd09 的签名是 (lng, lat)
                                double[] bdLonLat = MapUtils.wgs2bd09(dialog_lng_double, dialog_lat_double);
                                mMarkLatLngMap = new GeoPoint(bdLonLat[1], bdLonLat[0]);
                            }
                            mMarkName = "手动输入的坐标";

                            markMap();

                            // 绘制时再转成 WGS-84
                            mMapView.getController().setCenter(
                                    bd09ToWgs84(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude()));

                            dialog.dismiss();
                        }
                    }
                }
            });

            Button btnCancel = view.findViewById(R.id.input_position_cancel);
            btnCancel.setOnClickListener(v1 -> dialog.dismiss());
        });
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
    }

    private void startGoLocation() {
        Intent serviceGoIntent = new Intent(MainActivity.this, ServiceGo.class);
        bindService(serviceGoIntent, mConnection, BIND_AUTO_CREATE);    // 绑定服务和活动，之后活动就可以去调服务的方法了
        double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
        serviceGoIntent.putExtra(LNG_MSG_ID, latLng[0]);
        serviceGoIntent.putExtra(LAT_MSG_ID, latLng[1]);
        double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
        serviceGoIntent.putExtra(ALT_MSG_ID, alt);

        startForegroundService(serviceGoIntent);
        XLog.d("startForegroundService: ServiceGo");

        isMockServStart = true;
    }

    private void stopGoLocation() {
        unbindService(mConnection); // 解绑服务，服务要记得解绑，不要造成内存泄漏
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
                double[] latLng = MapUtils.bd2wgs(mMarkLatLngMap.getLongitude(), mMarkLatLngMap.getLatitude());
                double alt = Double.parseDouble(sharedPreferences.getString("setting_altitude", "55.0"));
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
