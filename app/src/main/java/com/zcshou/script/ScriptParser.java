package com.zcshou.script;

import com.zcshou.utils.MapUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本文本解析器。
 *
 * <p>脚本格式：一行一个路点，格式为
 * {@code 状态 [经度] [纬度] [速度] [停留秒数]}，用空格或逗号分隔，例如：</p>
 *
 * <pre>
 * # 以 # 或 // 开头的是注释，空行会被忽略
 * walk 116.397,39.908         步行（速度取设置里的步行速度）
 * run  116.399,39.909 4.5     跑步，速度 4.5 米/秒
 * bike 116.405,39.910 20km/h  骑行，速度 20 公里/小时
 * walk 116.410 39.910 1.2 10  步行 1.2 米/秒，到达后原地停留 10 秒
 * </pre>
 *
 * <p>坐标默认按 WGS-84 解析（与手机系统定位一致），也可以按 BD-09 解析，
 * 但需要显式指定，因为两套坐标的值域完全重叠、无法自动识别。</p>
 */
public class ScriptParser {
    /** 记忆“脚本里的经纬度是否为 BD-09”，编辑页与服务端解析共用 */
    public static final String KEY_SCRIPT_FROM_BD09 = "script_from_bd09";

    /** 脚本里可以使用的状态关键字 */
    private static final String[] MODE_KEYWORDS = {
            "walk", "run", "bike", "cycle", "riding", "walking", "running",
            "步行", "走路", "行走", "跑步", "奔跑", "骑行", "骑车", "单车", "自行车"
    };

    /** 速度标签：标签后面第一个数字优先当作速度，避免和坐标混淆 */
    private static final String[] SPEED_LABELS = {"速度", "speed", "v="};
    /** 停留标签 */
    private static final String[] WAIT_LABELS = {"停留", "等待", "wait", "pause"};

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern LEADING_MODE_PATTERN = Pattern.compile("^[A-Za-z\\u4e00-\\u9fa5]+");

    private ScriptParser() {
    }

    /** 解析结果：成功时 route 可用，失败时 error 里是给用户看的原因 */
    public static class ParseResult {
        public ScriptRoute route;
        public final List<String> warnings = new ArrayList<>();
        public String error;

        public boolean isOk() {
            return route != null && error == null;
        }

        /** 校验信息：错误优先，其次是警告 */
        public String getMessage() {
            StringBuilder builder = new StringBuilder();
            if (error != null) {
                builder.append(error).append('\n');
            }
            for (String warning : warnings) {
                builder.append(warning).append('\n');
            }
            return builder.toString().trim();
        }
    }

    /**
     * 解析脚本文本。
     *
     * @param text   脚本内容
     * @param fromBd09 文本里的经纬度是否为 BD-09 坐标系
     * @param modeSpeeds 各状态在“设置”里的默认速度，长度需与 {@link ScriptWaypoint.Mode#values()} 一致
     */
    public static ParseResult parse(String text, boolean fromBd09, double[] modeSpeeds) {
        ParseResult result = new ParseResult();

        if (text == null || text.trim().isEmpty()) {
            result.error = "脚本内容为空，请先写入路点";
            return result;
        }

        ScriptRoute route = new ScriptRoute();
        route.text = text;

        String[] lines = text.split("\r\n|\r|\n");
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            LineParse lineParse = parseLine(lines[i], fromBd09, modeSpeeds);

            if (lineParse.skip) {
                continue;
            }
            if (lineParse.error != null) {
                result.error = "第 " + lineNo + " 行：" + lineParse.error;
                return result;
            }
            if (lineParse.warning != null) {
                result.warnings.add("第 " + lineNo + " 行：" + lineParse.warning);
            }

            ScriptWaypoint point = lineParse.point;
            if (point == null) {
                continue;
            }

            // 相邻完全重合的点没有意义，直接忽略（只保留最后一个状态）
            if (!route.points.isEmpty()) {
                ScriptWaypoint last = route.points.get(route.points.size() - 1);
                if (almostSame(last.lng, point.lng) && almostSame(last.lat, point.lat)) {
                    if (last.mode != point.mode || Math.abs(last.speed - point.speed) > 1e-6) {
                        route.points.set(route.points.size() - 1, point);
                        result.warnings.add("第 " + lineNo + " 行：与上一个路点位置重复，已按新状态覆盖");
                    } else {
                        result.warnings.add("第 " + lineNo + " 行：与上一个路点位置重复，已忽略");
                    }
                    continue;
                }
            }

            route.points.add(point);
        }

        if (route.points.isEmpty()) {
            result.error = "没有解析到任何路点，请检查脚本格式";
            return result;
        }

        result.route = route;
        return result;
    }

    private static boolean almostSame(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    /*============================== 带行号的解析（供地图选点） ==============================*/

    /**
     * 带行号的路点：行号从 0 开始，用来把“地图上的第 N 个路点”映射回脚本原文的那一行，
     * 地图选点里删除 / 改状态 / 调顺序都只动这一行。
     */
    public static class ParsedLine {
        /** 该路点在脚本原文里的行号（0 起） */
        public final int lineIndex;
        /** 解析出的路点（WGS-84） */
        public final ScriptWaypoint point;

        public ParsedLine(int lineIndex, ScriptWaypoint point) {
            this.lineIndex = lineIndex;
            this.point = point;
        }
    }

    /**
     * 逐行解析并保留行号：注释 / 空行跳过、相邻重合点的合并规则与 {@link #parse} 完全一致，
     * 解析失败的行直接忽略（错误提示交给 {@link #parse} 负责）。
     *
     * @return 按路点顺序排列的条目，lineIndex 可直接定位到原文
     */
    public static List<ParsedLine> parseLines(String text, boolean fromBd09, double[] modeSpeeds) {
        List<ParsedLine> entries = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return entries;
        }

        String[] lines = text.split("\r\n|\r|\n");
        for (int i = 0; i < lines.length; i++) {
            LineParse lineParse = parseLine(lines[i], fromBd09, modeSpeeds);
            if (lineParse.skip || lineParse.error != null || lineParse.point == null) {
                continue;
            }

            ScriptWaypoint point = lineParse.point;
            // 与 parse() 相同的“相邻完全重合”合并规则，保证地图上的点数和校验摘要一致
            if (!entries.isEmpty()) {
                ParsedLine lastEntry = entries.get(entries.size() - 1);
                if (almostSame(lastEntry.point.lng, point.lng) && almostSame(lastEntry.point.lat, point.lat)) {
                    if (lastEntry.point.mode != point.mode
                            || Math.abs(lastEntry.point.speed - point.speed) > 1e-6) {
                        entries.set(entries.size() - 1, new ParsedLine(i, point));
                    }
                    continue;
                }
            }

            entries.add(new ParsedLine(i, point));
        }

        return entries;
    }

    /** 单行解析的中间结果 */
    private static class LineParse {
        ScriptWaypoint point;
        String error;
        String warning;
        boolean skip;
    }

    private static LineParse parseLine(String rawLine, boolean fromBd09, double[] modeSpeeds) {
        LineParse lineParse = new LineParse();

        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) {
            lineParse.skip = true;
            return lineParse;
        }

        // 去掉行尾注释
        int commentIndex = line.indexOf('#');
        if (commentIndex > 0) {
            line = line.substring(0, commentIndex).trim();
        }
        if (line.isEmpty()) {
            lineParse.skip = true;
            return lineParse;
        }

        // 1) 状态关键字
        ScriptWaypoint.Mode mode = null;
        String leading = null;
        Matcher leadingMatcher = LEADING_MODE_PATTERN.matcher(line);
        if (leadingMatcher.find()) {
            leading = leadingMatcher.group();
            mode = ScriptWaypoint.Mode.parse(leading);
        }
        if (mode == null) {
            lineParse.error = "缺少移动状态，请以 步行/跑步/骑行（walk/run/bike）开头";
            return lineParse;
        }

        // 2) 标签指定的速度 / 停留时间（标签写法优先，避免和坐标混淆）
        String afterMode = line.substring(leading.length());
        double labeledSpeed = findLabeledNumber(afterMode, SPEED_LABELS);
        double labeledWait = findLabeledNumber(afterMode, WAIT_LABELS);

        // 3) 剩余数字：前两个是坐标，之后依次是速度、停留时间
        List<Double> numbers = new ArrayList<>();
        Matcher numberMatcher = NUMBER_PATTERN.matcher(afterMode);
        while (numberMatcher.find()) {
            try {
                numbers.add(Double.parseDouble(numberMatcher.group()));
            } catch (NumberFormatException e) {
                lineParse.error = "数字格式不正确：" + numberMatcher.group();
                return lineParse;
            }
        }

        if (numbers.size() < 2) {
            lineParse.error = "缺少经纬度，格式为：状态 经度 纬度 [速度] [停留秒数]";
            return lineParse;
        }

        double lng = numbers.get(0);
        double lat = numbers.get(1);

        if (fromBd09) {
            double[] wgs84 = MapUtils.bd2wgs(lng, lat);
            lng = wgs84[0];
            lat = wgs84[1];
        }

        if (lat > 90.0 || lat < -90.0) {
            lineParse.error = "纬度超出范围（-90 ~ 90）：" + lat;
            return lineParse;
        }
        if (lng > 180.0 || lng < -180.0) {
            lineParse.error = "经度超出范围（-180 ~ 180）：" + lng;
            return lineParse;
        }

        double speed = 0;
        if (labeledSpeed > 0) {
            speed = labeledSpeed;
        } else if (numbers.size() > 2) {
            speed = numbers.get(2);
        }
        if (speed < 0) {
            speed = 0;
        }

        // 公里/小时写法换算成米/秒
        String lower = afterMode.toLowerCase(Locale.US);
        if (speed > 0 && (lower.contains("km/h") || lower.contains("kmh") || lower.contains("千米/时")
                || lower.contains("公里/时") || lower.contains("码"))) {
            speed = speed / 3.6;
        }

        int wait = 0;
        if (labeledWait > 0) {
            wait = (int) Math.round(labeledWait);
        } else if (numbers.size() > 3) {
            wait = (int) Math.round(numbers.get(3));
        }
        if (wait < 0) {
            wait = 0;
        }

        // 速度没写时，用状态对应的默认速度（只区分走 / 跑 / 骑）
        if (speed <= 0 && modeSpeeds != null && modeSpeeds.length > mode.ordinal()) {
            speed = modeSpeeds[mode.ordinal()];
        }

        lineParse.point = new ScriptWaypoint(lng, lat, ServiceDefaults.ALTITUDE, mode, speed, wait);
        return lineParse;
    }

    /** 取标签后面出现的第一个数字 */
    private static double findLabeledNumber(String text, String[] labels) {
        String lower = text.toLowerCase(Locale.US);

        for (String label : labels) {
            int index = lower.indexOf(label);
            if (index < 0) {
                continue;
            }

            Matcher matcher = NUMBER_PATTERN.matcher(text);
            while (matcher.find()) {
                if (matcher.start() >= index + label.length()) {
                    try {
                        return Double.parseDouble(matcher.group());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }

        return 0;
    }

    /** 脚本解析时的默认海拔（设置里的海拔在 ServiceGo 里读取，这里只用于路点占位） */
    private static class ServiceDefaults {
        private static final double ALTITUDE = 55.0D;
    }

    /*============================== 脚本生成 ==============================*/

    /**
     * 把一个路点格式化成脚本里的一行，供编辑页“插入路点”使用。
     *
     * @param bd09 是否按 BD-09 输出坐标
     */
    public static String formatPoint(ScriptWaypoint point, boolean bd09) {
        double lng = point.lng;
        double lat = point.lat;

        if (bd09) {
            double[] bd = MapUtils.wgs2bd09(lng, lat);
            lng = bd[0];
            lat = bd[1];
        }

        return String.format(Locale.US, "%s %.6f %.6f  %.1f", point.mode.key, lng, lat, point.speed);
    }

    /*============================== 统计 ==============================*/

    /** 路点之间的地面距离（米） */
    public static double distance(ScriptWaypoint from, ScriptWaypoint to) {
        if (from == null || to == null) {
            return 0;
        }

        double lng1 = Math.toRadians(from.lng);
        double lat1 = Math.toRadians(from.lat);
        double lng2 = Math.toRadians(to.lng);
        double lat2 = Math.toRadians(to.lat);

        double sinLat = Math.sin((lat2 - lat1) / 2.0);
        double sinLng = Math.sin((lng2 - lng1) / 2.0);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;

        return 2.0 * ScriptPlayer.EARTH_RADIUS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 整条路线的长度（米），循环时额外包含“最后一点回到第一点”的闭合段 */
    public static double totalDistance(ScriptRoute route) {
        if (route == null || route.points == null || route.points.size() < 2) {
            return 0;
        }

        double total = 0;
        for (int i = 1; i < route.points.size(); i++) {
            total += distance(route.points.get(i - 1), route.points.get(i));
        }
        if (route.isLoop()) {
            total += distance(route.points.get(route.points.size() - 1), route.points.get(0));
        }

        return total;
    }

    /**
     * 预计耗时（秒）。
     *
     * <p>与 {@link ScriptPlayer} 的实际推进保持一致：段内速度从起点速度线性过渡到终点速度，
     * 平均速度为两者之和的一半；停留时间算在“到达的那个点”上，起点的停留不计
     * （循环模式下最后一点回到起点的闭合段会在起点停留，所以循环时起点也算一次）。</p>
     */
    public static long totalSeconds(ScriptRoute route) {
        if (route == null || route.points == null || route.points.isEmpty()) {
            return 0;
        }

        long seconds = 0;
        for (int i = 1; i < route.points.size(); i++) {
            seconds += segmentSeconds(route.points.get(i - 1), route.points.get(i));
            seconds += Math.max(0, route.points.get(i).waitSeconds);
        }
        if (route.isLoop() && route.points.size() > 1) {
            ScriptWaypoint first = route.points.get(0);
            ScriptWaypoint last = route.points.get(route.points.size() - 1);
            seconds += segmentSeconds(last, first);
            seconds += Math.max(0, first.waitSeconds);
        }

        return seconds;
    }

    /** 走完一段的预计秒数：平均速度取两端速度的算术平均，与播放器的插值一致 */
    private static long segmentSeconds(ScriptWaypoint from, ScriptWaypoint to) {
        double v0 = speedOr(from, 1.2);
        double v1 = speedOr(to, 1.2);
        double average = (v0 + v1) / 2.0;
        if (average <= 0) {
            average = 1.2;
        }

        return (long) Math.ceil(distance(from, to) / average);
    }

    /** 取路点速度，非法（<=0）时回落到默认值 */
    private static double speedOr(ScriptWaypoint point, double fallback) {
        if (point == null || point.speed <= 0) {
            return fallback;
        }

        return point.speed;
    }

    /** 把米格式化成 km / m 的显示文本 */
    public static String formatDistance(double meters) {
        if (meters >= 1000) {
            return String.format(Locale.US, "%.2f km", meters / 1000.0);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    /** 把秒格式化成 分:秒 / 时:分:秒 的显示文本 */
    public static String formatDuration(long seconds) {
        long hour = seconds / 3600;
        long minute = (seconds % 3600) / 60;
        long second = seconds % 60;

        if (hour > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hour, minute, second);
        }
        return String.format(Locale.US, "%d:%02d", minute, second);
    }

    /** 计算从 from 到 to 的方位角（0 ~ 360，正北为 0） */
    public static double bearing(ScriptWaypoint from, ScriptWaypoint to) {
        if (from == null || to == null) {
            return 0;
        }

        double lat1 = Math.toRadians(from.lat);
        double lat2 = Math.toRadians(to.lat);
        double dLng = Math.toRadians(to.lng - from.lng);

        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);

        double degree = Math.toDegrees(Math.atan2(y, x));
        return (degree + 360.0) % 360.0;
    }

    /**
     * 计算某个位置到脚本轨迹的最短距离（米），用于开始脚本时自动对齐到最近的一段。
     */
    public static double distanceToRoute(ScriptRoute route, double lng, double lat) {
        if (route == null || route.points.isEmpty()) {
            return Double.MAX_VALUE;
        }

        double best = Double.MAX_VALUE;
        ScriptWaypoint target = new ScriptWaypoint(lng, lat, 0, ScriptWaypoint.Mode.WALK, 0, 0);

        for (ScriptWaypoint point : route.points) {
            best = Math.min(best, distance(point, target));
        }

        // 投影到每一段上再取最小值，避免“离路点远但就在线段上”被误判
        for (int i = 1; i < route.points.size(); i++) {
            best = Math.min(best, distanceToSegment(route.points.get(i - 1), route.points.get(i), lng, lat));
        }
        if (route.isLoop() && route.points.size() > 1) {
            best = Math.min(best, distanceToSegment(route.points.get(route.points.size() - 1), route.points.get(0), lng, lat));
        }

        return best;
    }

    /** 点到线段的距离（米），用局部的平面近似，路程级别足够精确 */
    private static double distanceToSegment(ScriptWaypoint from, ScriptWaypoint to, double lng, double lat) {
        double scale = Math.cos(Math.toRadians(lat));
        double ax = (to.lng - from.lng) * scale;
        double ay = to.lat - from.lat;
        double px = (lng - from.lng) * scale;
        double py = lat - from.lat;

        double lengthSquared = ax * ax + ay * ay;
        double t = 0;
        if (lengthSquared > 0) {
            t = (px * ax + py * ay) / lengthSquared;
            t = Math.max(0.0, Math.min(1.0, t));
        }

        double dx = px - ax * t;
        double dy = py - ay * t;

        // 度 -> 米
        double latMeters = dy * 111320.0;
        double lngMeters = dx * 111320.0;
        return Math.sqrt(latMeters * latMeters + lngMeters * lngMeters);
    }

    /** 判断一个点是否足够接近轨迹（默认 80 米内视为在路上） */
    public static boolean isOnRoute(ScriptRoute route, double lng, double lat, double toleranceMeters) {
        return distanceToRoute(route, lng, lat) <= toleranceMeters;
    }

    /** 取“设置”里的移动参数，顺序与 {@link ScriptWaypoint.Mode} 一致 */
    public static double[] modeSpeeds(double walk, double run, double bike) {
        return new double[] {walk, run, bike};
    }
}
