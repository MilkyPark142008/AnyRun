package com.zcshou.utils;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.elvishew.xlog.XLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 基于 OpenStreetMap Nominatim 的地点检索 / 逆地理编码，用于替代原工程中的
 * 百度 {@code SuggestionSearch} 与 {@code GeoCoder}。
 *
 * <p>本类返回的经纬度一律是 <b>WGS-84</b>，调用方需要按原有 BD-09 语义使用
 * {@link MapUtils#wgs2bd09(double, double)} / {@link MapUtils#bd2wgs(double, double)} 自行转换。</p>
 *
 * <p>所有回调都在主线程执行，可以直接操作 UI。</p>
 */
public class OsmGeocoder {

    private static final String SEARCH_URL = "https://nominatim.openstreetmap.org/search";
    private static final String REVERSE_URL = "https://nominatim.openstreetmap.org/reverse";
    /* Nominatim 的使用条款要求带上可识别的 User-Agent，否则会直接返回 403 */
    private static final String USER_AGENT = "GoGoGo-Android/1.12.3 (mock location tool)";

    private final OkHttpClient mHttpClient;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /** 一条地点信息，经纬度为 WGS-84 */
    public static class Poi {
        public String name = "";
        public String address = "";
        public double longitude = 0.0;
        public double latitude = 0.0;
    }

    public interface OnSearchListener {
        void onSearchResult(List<Poi> pois);
    }

    public interface OnReverseListener {
        void onReverseResult(Poi poi);
    }

    public OsmGeocoder(@NonNull OkHttpClient httpClient) {
        this.mHttpClient = httpClient;
    }

    /**
     * 按关键字检索地点
     *
     * @param keyword 关键字
     * @param listener 结果回调（主线程），失败时回传空列表
     */
    public void search(final String keyword, @NonNull final OnSearchListener listener) {
        if (TextUtils.isEmpty(keyword)) {
            listener.onSearchResult(Collections.emptyList());
            return;
        }

        String url;
        try {
            url = SEARCH_URL + "?format=jsonv2&limit=10&accept-language=zh-CN&q="
                    + URLEncoder.encode(keyword, "UTF-8");
        } catch (Exception e) {
            XLog.e("OSM: encode search keyword failed");
            listener.onSearchResult(Collections.emptyList());
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                XLog.e("OSM: search failed - " + e.getMessage());
                post(() -> listener.onSearchResult(Collections.emptyList()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                final List<Poi> result = new ArrayList<>();
                ResponseBody body = response.body();
                if (body != null) {
                    try {
                        JSONArray array = new JSONArray(body.string());
                        for (int i = 0; i < array.length(); i++) {
                            Poi poi = parsePoi(array.optJSONObject(i));
                            if (poi != null) {
                                result.add(poi);
                            }
                        }
                    } catch (Exception e) {
                        XLog.e("OSM: parse search result failed");
                    }
                }
                post(() -> listener.onSearchResult(result));
            }
        });
    }

    /**
     * 逆地理编码（由坐标查地址）
     *
     * @param latitude  WGS-84 纬度
     * @param longitude WGS-84 经度
     * @param listener  结果回调（主线程），失败时回传 null
     */
    public void reverse(final double latitude, final double longitude,
                        @NonNull final OnReverseListener listener) {
        final String url = REVERSE_URL + "?format=jsonv2&accept-language=zh-CN&zoom=18"
                + "&lat=" + latitude + "&lon=" + longitude;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();

        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                XLog.e("OSM: reverse failed - " + e.getMessage());
                post(() -> listener.onReverseResult(null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Poi poi = null;
                ResponseBody body = response.body();
                if (body != null) {
                    try {
                        poi = parsePoi(new JSONObject(body.string()));
                    } catch (Exception e) {
                        XLog.e("OSM: parse reverse result failed");
                    }
                }
                // 有些点位 Nominatim 不会回传坐标，这里兜底用请求坐标
                if (poi != null && (poi.latitude == 0.0 && poi.longitude == 0.0)) {
                    poi.latitude = latitude;
                    poi.longitude = longitude;
                }
                final Poi finalPoi = poi;
                post(() -> listener.onReverseResult(finalPoi));
            }
        });
    }

    private static Poi parsePoi(JSONObject obj) {
        if (obj == null) {
            return null;
        }

        double lat = obj.optDouble("lat", Double.NaN);
        double lon = obj.optDouble("lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null;
        }

        Poi poi = new Poi();
        poi.latitude = lat;
        poi.longitude = lon;
        poi.address = obj.optString("display_name", "");
        poi.name = obj.optString("name", "");
        if (TextUtils.isEmpty(poi.name)) {
            poi.name = poi.address;
        }
        return poi;
    }

    private void post(Runnable runnable) {
        mMainHandler.post(runnable);
    }
}
