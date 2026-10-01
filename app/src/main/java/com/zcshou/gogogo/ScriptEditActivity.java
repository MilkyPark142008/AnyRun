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
import org.osmdroid.views.overlay.Polyline;

import com.zcshou.script.ScriptParser;
import com.zcshou.script.ScriptRoute;
import com.zcshou.script.ScriptStore;
import com.zcshou.script.ScriptTextEditor;
import com.zcshou.script.ScriptWaypoint;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;
import com.zcshou.utils.TileSourceUtils;

import java.util.ArrayList;
import java.util.List;

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

    /** 地图选点对话框打开期间的地图（关闭后置空，避免操作打到已回收的地图上） */
    private MapView mPickMap;
    /** 路点连线：只用来看，点在连线上仍会继续加点 */
    private Polyline mPickLine;
    /** 已选路点的图钉：单击 = 编辑该点，长按拖动 = 挪位置 */
    private final List<Marker> mPickMarkers = new ArrayList<>();

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

        String routeId = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        if (!TextUtils.isEmpty(routeId)) {
            mRoute = mStore.load(routeId);
        }

        // 坐标系优先跟着脚本自己走；旧脚本没有记录时才回落到“上次使用的坐标系”
        boolean fromBd09;
        if (mRoute != null && mRoute.fromBd09 != null) {
            fromBd09 = mRoute.fromBd09;
        } else {
            fromBd09 = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean(ScriptParser.KEY_SCRIPT_FROM_BD09, false);
        }
        mCoordBd09.setChecked(fromBd09);
        mCoordWgs84.setChecked(!fromBd09);

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
        // 坐标系记在这条脚本自己身上，之后改全局单选也不会让它偏移
        mRoute.fromBd09 = fromBd09;
        // 标记“用户手动改过”，主界面重新选点时就不会再覆盖这条脚本
        mRoute.edited = true;

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

        // 初位置：脚本第一个点，或者上一次模拟的位置（有路点时随后会被 fitPickerPoints 校正）
        GeoPoint start = firstPointOrLastPosition();
        mapView.getController().setCenter(start);
        mPickMap = mapView;

        // 连线图层放最下面：只用来看，点在连线上仍要能继续加点
        mPickLine = new Polyline(mapView);
        mPickLine.getOutlinePaint().setColor(ContextCompat.getColor(this, R.color.colorAccent));
        mPickLine.getOutlinePaint().setStrokeWidth(8.0f);
        mapView.getOverlays().add(mPickLine);

        // 点击派发器：单击空白处 = 按当前状态往光标处插入一个路点。
        // osmdroid 按图层倒序派发，路点图钉在它上面，所以点在已有路点上不会走到这里。
        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                // 地图回调给的是 WGS-84；insertPoint 会按编辑页选定的坐标系落到脚本里
                insertPoint(p.getLongitude(), p.getLatitude());
                refreshPickerPoints();
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return singleTapConfirmedHelper(p);
            }
        }));

        // 把脚本里已有的路点画出来（历史选点可见、可编辑）
        refreshPickerPoints();
        // 等对话框完成布局后再把视野缩放到能装下全部路点
        mapView.post(this::fitPickerPoints);

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
            showPreview();
            refreshPickerPoints();
        });

        dialog.setOnDismissListener(d -> {
            try {
                mapView.onDetach();
            } catch (Exception e) {
                // 地图已经回收时忽略
            }
            mPickMap = null;
            mPickLine = null;
            mPickMarkers.clear();
        });
    }

    /*-------------------------- 选点地图上的路点渲染与编辑 --------------------------*/

    /** 当前文本按路点解析的结果（带行号，与校验摘要的点数一致） */
    private List<ScriptParser.ParsedLine> currentPickerEntries() {
        return ScriptParser.parseLines(mTextEdit.getText().toString(),
                mCoordBd09.isChecked(), getModeSpeeds());
    }

    /** 按当前文本重画地图上的路点与连线（增删改 / 拖动之后都要调） */
    private void refreshPickerPoints() {
        if (mPickMap == null) {
            return;
        }

        for (Marker marker : mPickMarkers) {
            marker.closeInfoWindow();
            mPickMap.getOverlays().remove(marker);
        }
        mPickMarkers.clear();

        List<ScriptParser.ParsedLine> entries = currentPickerEntries();
        List<GeoPoint> points = new ArrayList<>();
        Drawable baseIcon = ContextCompat.getDrawable(this, R.drawable.icon_gcoding);

        for (int i = 0; i < entries.size(); i++) {
            ScriptWaypoint point = entries.get(i).point;
            GeoPoint geoPoint = new GeoPoint(point.lat, point.lng);
            points.add(geoPoint);

            Marker marker = new Marker(mPickMap);
            if (baseIcon != null) {
                // 每个 Marker 需要各自持有 Drawable，避免共用一份被回收
                Drawable icon = baseIcon.getConstantState() != null
                        ? baseIcon.getConstantState().newDrawable() : baseIcon;
                marker.setIcon(icon);
            }
            marker.setAnchor(0.5f, 1.0f);
            marker.setPosition(geoPoint);
            marker.setTitle(getResources().getString(R.string.script_point_title, i + 1));
            marker.setSnippet(point.mode.label);
            marker.setDraggable(true);

            final int entryIndex = i;
            marker.setOnMarkerClickListener((m, mv) -> {
                showPointOptions(entryIndex);
                return true;
            });
            marker.setOnMarkerDragListener(new Marker.OnMarkerDragListener() {
                @Override
                public void onMarkerDragStart(Marker m) {
                }

                @Override
                public void onMarkerDrag(Marker m) {
                }

                @Override
                public void onMarkerDragEnd(Marker m) {
                    GeoPoint dropped = m.getPosition();
                    movePickerPoint(entryIndex, dropped.getLongitude(), dropped.getLatitude());
                }
            });

            mPickMap.getOverlays().add(marker);
            mPickMarkers.add(marker);
        }

        if (mPickLine != null) {
            mPickLine.setPoints(points);
        }
        mPickMap.invalidate();
    }

    /** 把视野缩放到能装下全部路点（对话框布局完成后调一次） */
    private void fitPickerPoints() {
        if (mPickMap == null) {
            return;
        }

        List<ScriptParser.ParsedLine> entries = currentPickerEntries();
        if (entries.isEmpty()) {
            return;
        }

        double minLat = 90.0;
        double maxLat = -90.0;
        double minLng = 180.0;
        double maxLng = -180.0;
        for (ScriptParser.ParsedLine entry : entries) {
            minLat = Math.min(minLat, entry.point.lat);
            maxLat = Math.max(maxLat, entry.point.lat);
            minLng = Math.min(minLng, entry.point.lng);
            maxLng = Math.max(maxLng, entry.point.lng);
        }

        double centerLat = (minLat + maxLat) / 2.0;
        double centerLng = (minLng + maxLng) / 2.0;
        mPickMap.getController().setCenter(new GeoPoint(centerLat, centerLng));

        if (entries.size() == 1) {
            return;    // 单点保持默认缩放
        }

        int width = Math.max(mPickMap.getWidth(), 480);
        int height = Math.max(mPickMap.getHeight(), 320);
        double spanLat = (maxLat - minLat) * 110540.0;
        double spanLng = (maxLng - minLng) * 111320.0 * Math.cos(Math.toRadians(centerLat));
        double spanMeters = Math.max(Math.max(spanLat, spanLng), 10.0);
        // Web 墨卡托的 米/像素 = 156543.03392 * cos(纬度) / 2^层级，反解出能装下全部路点的层级
        double metersPerPixelNeeded = spanMeters / (Math.min(width, height) * 0.8);
        double zoom = Math.log(156543.03392 * Math.cos(Math.toRadians(centerLat)) / metersPerPixelNeeded)
                / Math.log(2);
        zoom = Math.max(3.0, Math.min(19.0, zoom));
        mPickMap.getController().setZoom(zoom);
    }

    /** 点已有路点：改状态 / 调顺序 / 删除 */
    private void showPointOptions(final int entryIndex) {
        final List<ScriptParser.ParsedLine> entries = currentPickerEntries();
        if (entryIndex < 0 || entryIndex >= entries.size()) {
            return;
        }
        final int lineIndex = entries.get(entryIndex).lineIndex;

        new AlertDialog.Builder(this)
                .setTitle(getResources().getString(R.string.script_point_title, entryIndex + 1))
                .setItems(new String[] {
                        getResources().getString(R.string.script_point_mode),
                        getResources().getString(R.string.script_point_up),
                        getResources().getString(R.string.script_point_down),
                        getResources().getString(R.string.script_point_delete)},
                        (dialog, which) -> {
                            switch (which) {
                                case 0:
                                    choosePointMode(lineIndex);
                                    break;
                                case 1:
                                    movePoint(entryIndex, lineIndex, -1);
                                    break;
                                case 2:
                                    movePoint(entryIndex, lineIndex, 1);
                                    break;
                                default:
                                    deletePoint(lineIndex);
                                    break;
                            }
                        })
                .setNegativeButton(R.string.input_position_cancel, null)
                .show();
    }

    /** 修改某个路点的移动状态；速度原本就是旧状态默认值时，跟着换成新状态的默认值 */
    private void choosePointMode(final int lineIndex) {
        ScriptWaypoint current = null;
        for (ScriptParser.ParsedLine entry : currentPickerEntries()) {
            if (entry.lineIndex == lineIndex) {
                current = entry.point;
                break;
            }
        }
        if (current == null) {
            return;
        }

        final ScriptWaypoint point = current;
        final String[] labels = {
                getResources().getString(R.string.script_mode_walk),
                getResources().getString(R.string.script_mode_run),
                getResources().getString(R.string.script_mode_bike)};

        new AlertDialog.Builder(this)
                .setTitle(R.string.script_point_mode)
                .setSingleChoiceItems(labels, Math.max(0, point.mode.ordinal()), (dialog, which) -> {
                    ScriptWaypoint.Mode newMode = modeAt(which);
                    if (newMode != null && newMode != point.mode) {
                        String text = ScriptTextEditor.replaceMode(
                                mTextEdit.getText().toString(), lineIndex, newMode.key);
                        if (Math.abs(point.speed - getModeSpeed(point.mode)) < 1e-6) {
                            text = ScriptTextEditor.replaceSpeed(text, lineIndex, getModeSpeed(newMode));
                        }
                        applyPickerText(text, R.string.script_point_updated);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.input_position_cancel, null)
                .show();
    }

    private static ScriptWaypoint.Mode modeAt(int position) {
        if (position == 1) {
            return ScriptWaypoint.Mode.RUN;
        }
        if (position == 2) {
            return ScriptWaypoint.Mode.BIKE;
        }
        return ScriptWaypoint.Mode.WALK;
    }

    /** 与上 / 下一个路点交换顺序（按路点顺序，注释行留在原地） */
    private void movePoint(int entryIndex, int lineIndex, int direction) {
        List<ScriptParser.ParsedLine> entries = currentPickerEntries();
        int other = entryIndex + direction;
        if (entryIndex < 0 || entryIndex >= entries.size() || other < 0 || other >= entries.size()) {
            return;     // 已经在最前 / 最后
        }

        String text = ScriptTextEditor.swapLines(mTextEdit.getText().toString(),
                lineIndex, entries.get(other).lineIndex);
        applyPickerText(text, R.string.script_point_moved);
    }

    /** 删除某个路点（只删它那一行，注释与其它路点不受影响） */
    private void deletePoint(int lineIndex) {
        String text = ScriptTextEditor.deleteLine(mTextEdit.getText().toString(), lineIndex);
        applyPickerText(text, R.string.script_point_deleted);
    }

    /** 拖动结束：把该路点在原文里的坐标换成新位置 */
    private void movePickerPoint(int entryIndex, double lngWgs84, double latWgs84) {
        List<ScriptParser.ParsedLine> entries = currentPickerEntries();
        if (entryIndex < 0 || entryIndex >= entries.size()) {
            return;
        }

        // 脚本声明为 BD-09 时，写回的坐标也要转成 BD-09，保证整份脚本坐标系一致
        double lng = lngWgs84;
        double lat = latWgs84;
        if (mCoordBd09.isChecked()) {
            double[] bd09 = MapUtils.wgs2bd09(lngWgs84, latWgs84);
            lng = bd09[0];
            lat = bd09[1];
        }

        String text = ScriptTextEditor.replaceCoords(mTextEdit.getText().toString(),
                entries.get(entryIndex).lineIndex, lng, lat);
        applyPickerText(text, R.string.script_point_dropped);
    }

    /** 用新文本替换编辑框内容（保留光标位置），并同步实时预览与地图上的路点 */
    private void applyPickerText(String newText, int messageRes) {
        int selection = mTextEdit.getSelectionStart();
        mTextEdit.setText(newText);
        int length = mTextEdit.getText().length();
        mTextEdit.setSelection(Math.max(0, Math.min(Math.max(selection, 0), length)));

        showPreview();
        refreshPickerPoints();

        GoUtils.DisplayToast(this, getResources().getString(messageRes));
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
