package com.zcshou.gogogo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.zcshou.script.ScriptParser;
import com.zcshou.script.ScriptRoute;
import com.zcshou.script.ScriptStore;
import com.zcshou.script.ScriptWaypoint;
import com.zcshou.service.ServiceGo;
import com.zcshou.utils.GoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本模式：管理脚本并启动“按脚本预设自动移动”的模拟位置。
 *
 * <p>列表里可以直接开始播放某个脚本，长按删除；点条目进入编辑页。</p>
 */
public class ScriptActivity extends BaseActivity {
    /** 编辑页返回时用来判断是否需要刷新 */
    private static final int REQUEST_EDIT = 1001;

    private ScriptStore mStore;
    private final List<ScriptRoute> mRoutes = new ArrayList<>();
    private ScriptAdapter mAdapter;

    private ListView mListView;
    private TextView mNoScriptText;

    /* 服务绑定，用于拿到播放状态 */
    private ServiceGo.ServiceGoBinder mServiceBinder;
    private boolean mBound;
    private String mPlayingScriptId;
    private int mPlayingIndex;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mServiceBinder = (ServiceGo.ServiceGoBinder) service;
            mBound = true;
            mServiceBinder.setScriptListener(mScriptListener);

            mPlayingScriptId = mServiceBinder.getRunningScriptId();
            notifyAdapter();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBinder = null;
            mBound = false;
        }
    };

    /** 服务端的播放状态变化回调（在定位线程上触发，需要切回主线程刷新界面） */
    private final ServiceGo.ScriptListener mScriptListener = new ServiceGo.ScriptListener() {
        @Override
        public void onScriptSegment(ScriptRoute route, int index, ScriptWaypoint.Mode mode, double speed) {
            mPlayingIndex = index;
            mPlayingScriptId = route == null ? null : route.id;
            notifyAdapter();
        }

        @Override
        public void onScriptFinish(ScriptRoute route) {
            mPlayingScriptId = route == null ? null : route.id;
            notifyAdapter();
        }

        @Override
        public void onScriptStopped() {
            mPlayingScriptId = null;
            notifyAdapter();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_script);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        mStore = new ScriptStore(this);

        mListView = findViewById(R.id.script_list_view);
        mNoScriptText = findViewById(R.id.script_no_textview);

        mAdapter = new ScriptAdapter();
        mListView.setAdapter(mAdapter);

        mListView.setOnItemClickListener((parent, view, position, id) -> openEditor(mRoutes.get(position)));
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(mRoutes.get(position));
            return true;
        });

        FloatingActionButton addButton = findViewById(R.id.script_add);
        addButton.setOnClickListener(v -> openEditor(null));

        refreshList();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // 注意：这里不能无条件 bindService，否则“只是想看看脚本列表”也会把模拟位置服务拉起来
        // （服务会立刻挂上摇杆悬浮窗）。只有确认有脚本在跑时才绑定。
        bindIfScriptRunning();
    }

    /** 有脚本正在播放时绑定服务，用于获取播放进度 */
    private void bindIfScriptRunning() {
        if (mStore.getRunningScriptId() == null) {
            return;
        }

        bindServiceIfNeeded();
    }

    /** 刚刚主动启动过服务，此时不必再判断存储状态 */
    private void bindAfterStart() {
        bindServiceIfNeeded();
    }

    private void bindServiceIfNeeded() {
        if (mBound) {
            return;
        }

        try {
            bindService(new Intent(this, ServiceGo.class), mConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            mBound = false;
        }
    }

    @Override
    protected void onStop() {
        if (mBound && mServiceBinder != null) {
            try {
                mServiceBinder.clearScriptListener();
            } catch (Exception ignored) {
                // 服务已退出时忽略
            }
        }
        if (mBound) {
            try {
                unbindService(mConnection);
            } catch (Exception ignored) {
                // 忽略未绑定异常
            }
            mBound = false;
            mServiceBinder = null;
        }

        super.onStop();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 编辑页保存后回来刷新列表 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_EDIT) {
            refreshList();
        }
    }

    private void openEditor(ScriptRoute route) {
        Intent intent = new Intent(this, ScriptEditActivity.class);
        if (route != null) {
            intent.putExtra(ScriptEditActivity.EXTRA_ROUTE_ID, route.id);
        }
        startActivityForResult(intent, REQUEST_EDIT);
    }

    private void refreshList() {
        mRoutes.clear();
        mRoutes.addAll(mStore.loadAll());

        // 服务还没绑定回来时，用存储里的运行状态兜底
        if (!mBound) {
            mPlayingScriptId = mStore.getRunningScriptId();
        }

        notifyAdapter();
    }

    private void notifyAdapter() {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }

            boolean empty = mRoutes.isEmpty();
            mNoScriptText.setVisibility(empty ? View.VISIBLE : View.GONE);
            mListView.setVisibility(empty ? View.GONE : View.VISIBLE);
            mAdapter.notifyDataSetChanged();
        });
    }

    /*============================== 开始 / 删除 ==============================*/

    private void startPlay(ScriptRoute route) {
        if (route == null || route.points.isEmpty()) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_need_point));
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

        // 以脚本第一个路点作为起始位置（ServiceGo 会把脚本对齐到离它最近的轨迹点）
        ScriptWaypoint first = route.points.get(0);
        Intent intent = new Intent(this, ServiceGo.class);
        intent.putExtra(MainActivity.SCRIPT_ROUTE_ID, route.id);
        intent.putExtra(MainActivity.LNG_MSG_ID, first.lng);
        intent.putExtra(MainActivity.LAT_MSG_ID, first.lat);
        intent.putExtra(MainActivity.ALT_MSG_ID, first.alt);

        try {
            startForegroundService(intent);
            mPlayingScriptId = route.id;
            mPlayingIndex = 0;
            notifyAdapter();

            // 服务是异步起来的，稍后再绑定一次以获取播放进度回调
            mListView.postDelayed(this::bindAfterStart, 800);

            GoUtils.DisplayToast(this, getResources().getString(R.string.script_start));
        } catch (Exception e) {
            GoUtils.DisplayToast(this, getResources().getString(R.string.script_start_fail));
        }
    }

    private void confirmDelete(ScriptRoute route) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.script_delete_title)
                .setMessage(getResources().getString(R.string.script_delete_message, safeName(route)))
                .setPositiveButton(R.string.script_delete_title, (dialog, which) -> {
                    mStore.delete(route.id);
                    if (route.id.equals(mPlayingScriptId)) {
                        stopPlaying();
                    }
                    refreshList();
                    GoUtils.DisplayToast(ScriptActivity.this, getResources().getString(R.string.script_delete_ok));
                })
                .setNegativeButton(R.string.input_position_cancel, (dialog, which) -> {
                })
                .show();
    }

    /** 正在播放的脚本被删除时，顺手停掉播放 */
    private void stopPlaying() {
        if (mBound && mServiceBinder != null) {
            try {
                mServiceBinder.stopScript();
            } catch (Exception ignored) {
                // 忽略服务异常
            }
        } else {
            Intent intent = new Intent(this, ServiceGo.class);
            try {
                stopService(intent);
            } catch (Exception ignored) {
                // 忽略服务异常
            }
        }

        mPlayingScriptId = null;
        notifyAdapter();
    }

    private static String safeName(ScriptRoute route) {
        if (route == null || route.name == null || route.name.trim().isEmpty()) {
            return "";
        }
        return route.name.trim();
    }

    /*============================== 列表适配器 ==============================*/

    private class ScriptAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(ScriptActivity.this);

        @Override
        public int getCount() {
            return mRoutes.size();
        }

        @Override
        public Object getItem(int position) {
            return mRoutes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.script_item, parent, false);
            }

            ScriptRoute route = mRoutes.get(position);
            if (route == null) {
                return view;
            }

            TextView name = view.findViewById(R.id.script_item_name);
            TextView badge = view.findViewById(R.id.script_item_badge);
            TextView loop = view.findViewById(R.id.script_item_loop);
            TextView info = view.findViewById(R.id.script_item_info);
            TextView status = view.findViewById(R.id.script_item_status);
            ImageButton start = view.findViewById(R.id.script_item_start);

            name.setText(safeName(route));

            // 状态角标：单一状态显示“走 / 跑 / 骑”，混用状态提示“含多种状态”
            ScriptWaypoint.Mode mode = primaryMode(route);
            if (mode == null) {
                badge.setVisibility(View.GONE);
            } else {
                badge.setVisibility(View.VISIBLE);
                badge.setText(modeLabel(mode));
                badge.setBackgroundColor(modeColor(mode));
            }

            loop.setText(route.isLoop() ? R.string.script_loop_on : R.string.script_loop_off);
            loop.setBackgroundResource(route.isLoop() ? R.drawable.round_badge_accent : R.drawable.round_badge_gray);

            double distance = ScriptParser.totalDistance(route);
            long seconds = ScriptParser.totalSeconds(route);
            info.setText(getResources().getString(R.string.script_item_info,
                    route.size(),
                    ScriptParser.formatDistance(distance),
                    ScriptParser.formatDuration(seconds)));

            boolean playing = route.id.equals(mPlayingScriptId);
            if (playing) {
                status.setVisibility(View.VISIBLE);
                int index = Math.min(mPlayingIndex + 1, Math.max(1, route.size()));
                status.setText(getResources().getString(R.string.script_playing, index, route.size(), modeLabel(mode)));
            } else {
                status.setVisibility(View.GONE);
                status.setText("");
            }

            start.setOnClickListener(v -> startPlay(route));

            return view;
        }
    }

    private static ScriptWaypoint.Mode primaryMode(ScriptRoute route) {
        if (route == null || route.points == null || route.points.isEmpty()) {
            return null;
        }

        ScriptWaypoint.Mode mode = route.points.get(0).mode;
        for (ScriptWaypoint point : route.points) {
            if (point.mode != mode) {
                return null;
            }
        }

        return mode;
    }

    private String modeLabel(ScriptWaypoint.Mode mode) {
        if (mode == null) {
            return getResources().getString(R.string.script_badge_multi);
        }
        return mode.label;
    }

    private int modeColor(ScriptWaypoint.Mode mode) {
        int colorRes;
        switch (mode) {
            case RUN:
                colorRes = R.color.darkorange;
                break;
            case BIKE:
                colorRes = R.color.steelblue;
                break;
            case WALK:
            default:
                colorRes = R.color.colorAccent;
                break;
        }

        return ContextCompat.getColor(this, colorRes);
    }
}
