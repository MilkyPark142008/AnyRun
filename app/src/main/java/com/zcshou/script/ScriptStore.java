package com.zcshou.script;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 脚本存储：使用 SharedPreferences 保存脚本列表。
 *
 * <p>脚本数据量很小（几行到几百行文本），用 SharedPreferences 足够，
 * 也能避免为它引入数据库的版本迁移。</p>
 */
public class ScriptStore {
    private static final String KEY_SCRIPTS = "script_route_list";
    /** 当前正在播放的脚本 id（服务重启后用来恢复状态） */
    private static final String KEY_RUNNING = "script_running_id";

    /** 路点序列化时的字段分隔符 */
    private static final String POINT_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = ";";

    private final SharedPreferences mPreferences;

    public ScriptStore(Context context) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /*============================== 读写 ==============================*/

    /** 读取全部脚本（按保存顺序） */
    public List<ScriptRoute> loadAll() {
        List<ScriptRoute> routes = new ArrayList<>();
        String raw = mPreferences.getString(KEY_SCRIPTS, null);
        if (TextUtils.isEmpty(raw)) {
            return routes;
        }

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }

                ScriptRoute route = fromJson(object);
                if (route != null) {
                    routes.add(route);
                }
            }
        } catch (JSONException e) {
            XLog.e("SCRIPT: load failed");
        }

        return routes;
    }

    /** 按 id 读取单个脚本，不存在时返回 null */
    public ScriptRoute load(String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }

        for (ScriptRoute route : loadAll()) {
            if (id.equals(route.id)) {
                return route;
            }
        }

        return null;
    }

    /** 新增或覆盖保存（按 id 匹配） */
    public void save(ScriptRoute route) {
        if (route == null) {
            return;
        }

        List<ScriptRoute> routes = loadAll();
        boolean replaced = false;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).id.equals(route.id)) {
                routes.set(i, route);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            routes.add(route);
        }

        writeAll(routes);
    }

    public void delete(String id) {
        if (TextUtils.isEmpty(id)) {
            return;
        }

        List<ScriptRoute> routes = loadAll();
        List<ScriptRoute> remain = new ArrayList<>();
        for (ScriptRoute route : routes) {
            if (!id.equals(route.id)) {
                remain.add(route);
            }
        }

        writeAll(remain);
    }

    public boolean isEmpty() {
        return loadAll().isEmpty();
    }

    private void writeAll(List<ScriptRoute> routes) {
        JSONArray array = new JSONArray();
        for (ScriptRoute route : routes) {
            try {
                array.put(toJson(route));
            } catch (JSONException e) {
                XLog.e("SCRIPT: write failed");
            }
        }

        mPreferences.edit().putString(KEY_SCRIPTS, array.toString()).apply();
    }

    /*============================== 序列化 ==============================*/

    private static JSONObject toJson(ScriptRoute route) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", route.id);
        object.put("name", route.name == null ? "" : route.name);
        object.put("end", route.endMode);
        object.put("text", route.text == null ? "" : route.text);
        object.put("points", encodePoints(route.points));
        // 坐标系跟着脚本自己走：null（旧脚本）时不写字段，读取端再回落到全局设置
        if (route.fromBd09 != null) {
            object.put("bd09", route.fromBd09.booleanValue());
        }
        return object;
    }

    private static ScriptRoute fromJson(JSONObject object) {
        try {
            ScriptRoute route = new ScriptRoute();
            route.id = object.optString("id", route.id);
            if (TextUtils.isEmpty(route.id)) {
                route.id = java.util.UUID.randomUUID().toString();
            }
            route.name = object.optString("name", "");
            route.endMode = object.optInt("end", ScriptRoute.END_STOP);
            route.text = object.optString("text", "");
            route.points = decodePoints(object.optString("points", ""));
            if (object.has("bd09")) {
                route.fromBd09 = object.optBoolean("bd09", false);
            }
            return route;
        } catch (Exception e) {
            XLog.e("SCRIPT: parse failed");
            return null;
        }
    }

    /**
     * 路点编码：每行一个，字段用 ; 分隔（mode;lng;lat;alt;speed;wait）。
     *
     * <p>公开是为了让界面在旋屏 / 重建时也能用它把“还没开始移动的选点”
     * 塞进 savedInstanceState，避免用户白点一遍。</p>
     */
    public static String encodePoints(List<ScriptWaypoint> points) {
        if (points == null || points.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (ScriptWaypoint point : points) {
            if (builder.length() > 0) {
                builder.append(POINT_SEPARATOR);
            }
            builder.append(point.mode == null ? ScriptWaypoint.Mode.WALK.key : point.mode.key).append(FIELD_SEPARATOR)
                    .append(String.format(Locale.US, "%.7f", point.lng)).append(FIELD_SEPARATOR)
                    .append(String.format(Locale.US, "%.7f", point.lat)).append(FIELD_SEPARATOR)
                    .append(String.format(Locale.US, "%.2f", point.alt)).append(FIELD_SEPARATOR)
                    .append(String.format(Locale.US, "%.3f", point.speed)).append(FIELD_SEPARATOR)
                    .append(point.waitSeconds);
        }

        return builder.toString();
    }

    /** 路点解码，与 {@link #encodePoints(List)} 配对（恢复选点时也会用到） */
    public static List<ScriptWaypoint> decodePoints(String raw) {
        List<ScriptWaypoint> points = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return points;
        }

        for (String line : raw.split(POINT_SEPARATOR)) {
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            String[] fields = line.split(FIELD_SEPARATOR);
            if (fields.length < 6) {
                continue;
            }

            try {
                ScriptWaypoint point = new ScriptWaypoint(
                        Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]),
                        Double.parseDouble(fields[3]),
                        ScriptWaypoint.Mode.parse(fields[0]),
                        Double.parseDouble(fields[4]),
                        (int) Double.parseDouble(fields[5]));
                points.add(point);
            } catch (NumberFormatException e) {
                XLog.e("SCRIPT: point parse failed");
            }
        }

        return points;
    }

    /*============================== 运行状态 ==============================*/

    /** 当前正在播放的脚本 id（服务重启后用来恢复状态），没有在播放时返回 null */
    public String getRunningScriptId() {
        return mPreferences.getString(KEY_RUNNING, null);
    }

    public void setRunningScriptId(String id) {
        mPreferences.edit().putString(KEY_RUNNING, id).apply();
    }
}
