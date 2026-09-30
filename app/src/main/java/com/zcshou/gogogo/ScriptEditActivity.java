package com.zcshou.gogogo;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import com.zcshou.script.ScriptParser;
import com.zcshou.script.ScriptRoute;
import com.zcshou.script.ScriptStore;
import com.zcshou.script.ScriptWaypoint;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.TileSourceUtils;

/**
 * 脚本编辑页：文本脚本 + 地图选点。
 *
 * <p>文本框里一行一个路点，既可以直接粘贴经纬度，也可以点击地图按当前选中的状态
 * （走 / 跑 / 骑）往光标处插入一行。</p>
 */
public class ScriptEditActivity extends BaseActivity {
    /** 编辑已有脚本时传入脚本 id */
    public static final String EXTRA_ROUTE_ID = "EXTRA_ROUTE_ID";
    /** 地图选点时的默认缩放级别 */
    private static final double DEFAULT_ZOOM = 17.0;

    private ScriptStore mStore;
    private ScriptRoute mRoute;

    private EditText mNameEdit;
    private EditText mTextEdit;
    private CheckBox mLoopCheck;
    private TextView mPreviewText;
    private RadioButton mCoordWgs84;
    private RadioButton mCoordBd09;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script_edit);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        mStore = new ScriptStore(this);

        mNameEdit = findViewById(R.id.script_name);
        mTextEdit = findViewById(R.id.script_text);
        mLoopCheck = findViewById(R.id.script_loop);
        mPreviewText = findViewById(R.id.script_preview);
        mCoordWgs84 = findViewById(R.id.script_coord_wgs84);
        mCoordBd09 = findViewById(R.id.script_coord_bd09);

        // 记住上次使用的坐标系
        boolean fromBd09 = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(ScriptParser.KEY_SCRIPT_FROM_BD09, false);
        mCoordBd09.setChecked(fromBd09);
        mCoordWgs84.setChecked(!fromBd09);

        String routeId = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        if (!TextUtils.isEmpty(routeId)) {
            mRoute = mStore.load(routeId);
        }

        if (mRoute == null) {
            mRoute = new ScriptRoute();
            mRoute.name = getResources().getString(R.string.script_name_default);
            if (actionBar != null) {
                actionBar.setTitle(R.string.script_edit_new_title);
            }
        } else {
            if (actionBar != null) {
                actionBar.setTitle(R.string.script_edit_title);
            }
        }

        mNameEdit.setText(mRoute.name);
        mTextEdit.setText(mRoute.text);
        mLoopCheck.setChecked(mRoute.isLoop());

        findViewById(R.id.script_map_pick).setOnClickListener(v -> showMapPicker());
        findViewById(R.id.script_append_current).setOnClickListener(v -> appendCurrentPosition());
        findViewById(R.id.script_check).setOnClickListener(v -> checkScript());

        // 编辑过程中实时更新摘要，随时能看到脚本是否还合法
        mTextEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showPreview();
            }
        });

        showPreview();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_script_edit, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_script_save) {
            save();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*============================== 保存 / 校验 ==============================*/

    private void save() {
        String name = mNameEdit.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_need_name));
            return;
        }

        String text = mTextEdit.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_need_point));
            return;
        }

        boolean fromBd09 = mCoordBd09.isChecked();
        ScriptParser.ParseResult parseResult = ScriptParser.parse(text, fromBd09, getModeSpeeds());
        if (!parseResult.isOk()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_check_fail, parseResult.getMessage()));
            return;
        }

        // 脚本里没写海拔时，用设置里的海拔补齐
        double altitude = getSettingAltitude();
        for (ScriptWaypoint point : parseResult.route.points) {
            point.alt = altitude;
        }

        mRoute.name = name;
        mRoute.text = text;
        mRoute.points = parseResult.route.points;
        mRoute.endMode = mLoopCheck.isChecked() ? ScriptRoute.END_LOOP : ScriptRoute.END_STOP;

        mStore.save(mRoute);
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(ScriptParser.KEY_SCRIPT_FROM_BD09, fromBd09)
                .apply();

        GoUtils.DisplayToast(this, getResources().getString(R.string.script_saved));
        setResult(RESULT_OK);
        finish();
    }

    private void checkScript() {
        String text = mTextEdit.getText().toString();
        boolean fromBd09 = mCoordBd09.isChecked();
        ScriptParser.ParseResult parseResult = ScriptParser.parse(text, fromBd09, getModeSpeeds());

        if (!parseResult.isOk()) {
            mPreviewText.setText(getResources().getString(R.string.script_check_fail, parseResult.getMessage()));
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_check_fail, parseResult.error));
            return;
        }

        double distance = ScriptParser.totalDistance(parseResult.route);
        long seconds = ScriptParser.totalSeconds(parseResult.route);
        String summary = getResources().getString(R.string.script_check_ok,
                parseResult.route.size(),
                ScriptParser.formatDistance(distance),
                ScriptParser.formatDuration(seconds));

        if (parseResult.warnings.isEmpty()) {
            mPreviewText.setText(summary);
            GoUtils.DisplayToast(this, summary);
        } else {
            String message = summary + "\n" + String.join("\n", parseResult.warnings);
            mPreviewText.setText(message);
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_check_warn,
                    String.join("\n", parseResult.warnings)));
        }
    }

    /** 编辑过程中的实时摘要，随时提示当前脚本是否可用 */
    private void showPreview() {
        String text = mTextEdit.getText().toString();
        if (TextUtils.isEmpty(text.trim())) {
            mPreviewText.setText(R.string.script_format_tips);
            return;
        }

        ScriptParser.ParseResult parseResult = ScriptParser.parse(text, mCoordBd09.isChecked(), getModeSpeeds());
        if (!parseResult.isOk()) {
            mPreviewText.setText(getResources().getString(R.string.script_check_fail, parseResult.error));
            return;
        }

        mPreviewText.setText(getResources().getString(R.string.script_check_ok,
                parseResult.route.size(),
                ScriptParser.formatDistance(ScriptParser.totalDistance(parseResult.route)),
                ScriptParser.formatDuration(ScriptParser.totalSeconds(parseResult.route))));
    }

    /*============================== 插入路点 ==============================*/

    /** 当前选中的移动状态（走 / 跑 / 骑） */
    private ScriptWaypoint.Mode getInsertMode() {
        if (((RadioButton) findViewById(R.id.script_mode_run)).isChecked()) {
            return ScriptWaypoint.Mode.RUN;
        }
        if (((RadioButton) findViewById(R.id.script_mode_bike)).isChecked()) {
            return ScriptWaypoint.Mode.BIKE;
        }
        return ScriptWaypoint.Mode.WALK;
    }

    /**
     * 在光标处插入一个路点。
     *
     * @param lngWgs84 经度（WGS-84，地图回调与内部模拟坐标都是这个坐标系）
     * @param latWgs84 纬度（WGS-84）
     */
    private void insertPoint(double lngWgs84, double latWgs84) {
        ScriptWaypoint.Mode mode = getInsertMode();
        double speed = getModeSpeed(mode);

        // 脚本声明为 BD-09 时，插入的坐标也要转成 BD-09，保证整份脚本坐标系一致
        double lng = lngWgs84;
        double lat = latWgs84;
        if (mCoordBd09.isChecked()) {
            double[] bd09 = MapUtils.wgs2bd09(lngWgs84, latWgs84);
            lng = bd09[0];
            lat = bd09[1];
        }

        ScriptWaypoint point = new ScriptWaypoint(lng, lat, getSettingAltitude(), mode, speed, 0);
        String line = String.format(java.util.Locale.US, "%s %.6f %.6f  %.1f", mode.key, lng, lat, speed);

        int start = Math.max(0, Math.min(mTextEdit.getSelectionStart(), mTextEdit.getText().length()));
        int end = Math.max(start, Math.min(mTextEdit.getSelectionEnd(), mTextEdit.getText().length()));
        String text = mTextEdit.getText().toString();

        String prefix = "";
        if (start > 0 && text.charAt(start - 1) != '\n') {
            prefix = "\n";
        }

        mTextEdit.getText().replace(start, end, prefix + line);
        // 插入后把光标移到行尾，方便继续追加
        mTextEdit.setSelection(Math.min(mTextEdit.getText().length(), start + prefix.length() + line.length()));

        showPreview();
    }

    /** 把上一次模拟的位置作为路点插入 */
    private void appendCurrentPosition() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String lngText = preferences.getString("service_last_lng", null);
        String latText = preferences.getString("service_last_lat", null);

        if (TextUtils.isEmpty(lngText) || TextUtils.isEmpty(latText)) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_no_location_yet));
            return;
        }

        try {
            insertPoint(Double.parseDouble(lngText), Double.parseDouble(latText));
        } catch (NumberFormatException e) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_no_location_yet));
        }
    }

    /*============================== 地图选点 ==============================*/

    private void showMapPicker() {
        View view = LayoutInflater.from(this).inflate(R.layout.script_map_pick, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.script_map_pick)
                .setView(view)
                .create();
        dialog.show();

        MapView mapView = view.findViewById(R.id.script_map);
        mapView.setBuiltInZoomControls(false);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setTileSource(TileSourceUtils.OSM_STANDARD);
        mapView.getController().setZoom(DEFAULT_ZOOM);

        // 选点标记：每次新建一份 Drawable，避免与其它地图共用
        final Marker marker = new Marker(mapView);
        Drawable icon = ContextCompat.getDrawable(this, R.drawable.icon_gcoding);
        if (icon != null) {
            if (icon.getConstantState() != null) {
                icon = icon.getConstantState().newDrawable();
            }
            marker.setIcon(icon);
        }
        marker.setAnchor(0.5f, 1.0f);
        mapView.getOverlays().add(marker);

        // 初位置：脚本第一个点，或者上一次模拟的位置
        GeoPoint start = firstPointOrLastPosition();
        mapView.getController().setCenter(start);

        final Spinner modeSpinner = view.findViewById(R.id.script_map_mode);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[] {
                        getResources().getString(R.string.script_mode_walk),
                        getResources().getString(R.string.script_mode_run),
                        getResources().getString(R.string.script_mode_bike)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(adapter);
        // 与编辑页当前选中的状态保持一致
        ScriptWaypoint.Mode mode = getInsertMode();
        modeSpinner.setSelection(mode == ScriptWaypoint.Mode.RUN ? 1 : mode == ScriptWaypoint.Mode.BIKE ? 2 : 0);

        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                marker.setPosition(p);
                mapView.invalidate();

                // 地图回调给的是 WGS-84；insertPoint 会按编辑页选定的坐标系落到脚本里
                insertPoint(p.getLongitude(), p.getLatitude());
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return singleTapConfirmedHelper(p);
            }
        }));

        // 下拉框切换状态时同步回编辑页的单选按钮，避免两个入口状态不一致
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view1, int position, long id) {
                int radioId;
                if (position == 1) {
                    radioId = R.id.script_mode_run;
                } else if (position == 2) {
                    radioId = R.id.script_mode_bike;
                } else {
                    radioId = R.id.script_mode_walk;
                }
                ((RadioButton) findViewById(radioId)).setChecked(true);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        view.findViewById(R.id.script_map_close).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.script_map_clear).setOnClickListener(v -> {
            mTextEdit.setText("");
            mapView.getOverlays().remove(marker);
            mapView.invalidate();
            showPreview();
        });

        dialog.setOnDismissListener(d -> {
            try {
                mapView.onDetach();
            } catch (Exception e) {
                // 地图已经回收时忽略
            }
        });
    }

    /** 地图初始位置：脚本第一个点 > 上次模拟位置 > 默认点 */
    private GeoPoint firstPointOrLastPosition() {
        if (mRoute != null && !mRoute.points.isEmpty()) {
            ScriptWaypoint first = mRoute.points.get(0);
            return new GeoPoint(first.lat, first.lng);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        String lngText = preferences.getString("service_last_lng", null);
        String latText = preferences.getString("service_last_lat", null);
        if (!TextUtils.isEmpty(lngText) && !TextUtils.isEmpty(latText)) {
            try {
                return new GeoPoint(Double.parseDouble(latText), Double.parseDouble(lngText));
            } catch (NumberFormatException ignored) {
                // 落到默认位置
            }
        }

        // 与 ServiceGo 的默认位置保持一致
        return new GeoPoint(com.zcshou.service.ServiceGo.DEFAULT_LAT, com.zcshou.service.ServiceGo.DEFAULT_LNG);
    }

    /*============================== 参数读取 ==============================*/

    private double getSettingAltitude() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return parseDouble(preferences.getString("setting_altitude", "55.0"), 55.0);
    }

    private double[] getModeSpeeds() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        return ScriptParser.modeSpeeds(
                parseDouble(preferences.getString("setting_walk", "1.2"), 1.2),
                parseDouble(preferences.getString("setting_run", "3.6"), 3.6),
                parseDouble(preferences.getString("setting_bike", "10.0"), 10.0));
    }

    private double getModeSpeed(ScriptWaypoint.Mode mode) {
        double[] speeds = getModeSpeeds();
        return speeds[Math.max(0, Math.min(speeds.length - 1, mode.ordinal()))];
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
}
