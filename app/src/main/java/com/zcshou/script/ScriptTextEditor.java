package com.zcshou.script;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本原文的“外科手术式”编辑：只动需要动的那一行 / 那几个字符，
 * 注释、空行、自定义写法（20km/h、停留标签……）全部原样保留。
 *
 * <p>纯 Java 实现，不依赖 Android，方便直接在 JVM 上做单元测试；
 * 地图选点里的“删除 / 改状态 / 调顺序 / 拖动挪点”都走这里。</p>
 */
public final class ScriptTextEditor {
    /** 与 {@link ScriptParser} 一致的数字写法 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    /** 行首状态关键字（英文或中文） */
    private static final Pattern LEADING_MODE_PATTERN = Pattern.compile("^[A-Za-z\\u4e00-\\u9fa5]+");
    /** 与 String.split("\r\n|\r|\n") 一致的换行写法 */
    private static final Pattern LINE_PATTERN = Pattern.compile("\r\n|\r|\n");

    private ScriptTextEditor() {
    }

    /*============================== 行定位 ==============================*/

    /** 行首偏移（0 起行号）；行不存在返回 -1 */
    public static int lineStart(String text, int lineIndex) {
        if (text == null || lineIndex < 0) {
            return -1;
        }

        int pos = 0;
        for (int i = 0; i < lineIndex; i++) {
            pos = nextLineStart(text, pos);
            if (pos < 0) {
                return -1;
            }
        }

        return pos > text.length() ? -1 : pos;
    }

    /** 行尾偏移（不含换行符）；行不存在返回 -1 */
    public static int lineEnd(String text, int lineIndex) {
        int start = lineStart(text, lineIndex);
        if (start < 0) {
            return -1;
        }

        int newline = findNewline(text, start);
        return newline < 0 ? text.length() : newline;
    }

    /** 从 pos 开始下一行的行首偏移；pos 之后没有换行（已是最后一行）返回 -1 */
    private static int nextLineStart(String text, int pos) {
        if (pos >= text.length()) {
            return -1;
        }

        int newline = findNewline(text, pos);
        if (newline < 0) {
            return -1;
        }
        if (text.charAt(newline) == '\r' && newline + 1 < text.length() && text.charAt(newline + 1) == '\n') {
            return newline + 2;
        }
        return newline + 1;
    }

    /** 从 from 起第一个换行符位置；没有返回 -1 */
    private static int findNewline(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }

    /*============================== 行操作 ==============================*/

    /** 删除整行：优先吃掉它后面的换行（最后一行则吃掉前面的换行） */
    public static String deleteLine(String text, int lineIndex) {
        int start = lineStart(text, lineIndex);
        int end = lineEnd(text, lineIndex);
        if (start < 0 || end < 0) {
            return text;
        }

        int cutStart = start;
        int cutEnd = end;
        if (cutEnd < text.length()) {
            cutEnd++;
            if (text.charAt(end) == '\r' && cutEnd < text.length() && text.charAt(cutEnd) == '\n') {
                cutEnd++;
            }
        } else if (cutStart > 0) {
            int prev = cutStart - 1;
            if (text.charAt(prev) == '\n') {
                prev--;
            }
            if (prev >= 0 && text.charAt(prev) == '\r') {
                prev--;
            }
            cutStart = prev + 1;
        }

        return text.substring(0, cutStart) + text.substring(cutEnd);
    }

    /** 交换两行的行内容（行位置不动，注释 / 空行留在原地） */
    public static String swapLines(String text, int lineA, int lineB) {
        if (lineA == lineB) {
            return text;
        }

        int startA = lineStart(text, lineA);
        int endA = lineEnd(text, lineA);
        int startB = lineStart(text, lineB);
        int endB = lineEnd(text, lineB);
        if (startA < 0 || endA < 0 || startB < 0 || endB < 0) {
            return text;
        }

        boolean aFirst = startA < startB;
        int loStart = aFirst ? startA : startB;
        int loEnd = aFirst ? endA : endB;
        int hiStart = aFirst ? startB : startA;
        int hiEnd = aFirst ? endB : endA;
        String loContent = aFirst ? text.substring(startA, endA) : text.substring(startB, endB);
        String hiContent = aFirst ? text.substring(startB, endB) : text.substring(startA, endA);

        return text.substring(0, loStart) + hiContent
                + text.substring(loEnd, hiStart) + loContent
                + text.substring(hiEnd);
    }

    /**
     * 替换行首的状态关键字（保留坐标、速度、停留、注释等其余内容）。
     *
     * @param modeKey 新状态在脚本里的关键字（walk / run / bike）
     */
    public static String replaceMode(String text, int lineIndex, String modeKey) {
        String line = getLine(text, lineIndex);
        if (line == null || modeKey == null || modeKey.isEmpty()) {
            return text;
        }

        Matcher matcher = LEADING_MODE_PATTERN.matcher(line);
        String newLine = matcher.find()
                ? modeKey + line.substring(matcher.end())
                : modeKey + " " + line;

        return replaceLine(text, lineIndex, newLine);
    }

    /**
     * 替换该行“第 3 个数字”（速度）；这一行本来就没写速度（用状态默认值）时原样返回。
     */
    public static String replaceSpeed(String text, int lineIndex, double speed) {
        String line = getLine(text, lineIndex);
        if (line == null) {
            return text;
        }

        Matcher modeMatcher = LEADING_MODE_PATTERN.matcher(line);
        int from = modeMatcher.find() ? modeMatcher.end() : 0;

        // 注意：find(int) 会把匹配位置重置回 from，只能在进入循环前调一次，
        // 之后必须用无参 find() 续找，否则每次都命中同一个数字
        Matcher numberMatcher = NUMBER_PATTERN.matcher(line);
        if (!numberMatcher.find(from)) {
            return text;
        }

        int count = 1;
        while (count < 3 && numberMatcher.find()) {
            count++;
        }
        if (count < 3) {
            return text;
        }

        String newLine = line.substring(0, numberMatcher.start())
                + formatSpeed(speed) + line.substring(numberMatcher.end());
        return replaceLine(text, lineIndex, newLine);
    }

    /**
     * 替换该行的经纬度（前两个数字），状态 / 速度 / 停留 / 注释原样保留。
     *
     * <p>坐标按 {@code %.6f} 写回，与地图插点的精度一致。</p>
     */
    public static String replaceCoords(String text, int lineIndex, double lng, double lat) {
        String line = getLine(text, lineIndex);
        if (line == null) {
            return text;
        }

        Matcher modeMatcher = LEADING_MODE_PATTERN.matcher(line);
        int from = modeMatcher.find() ? modeMatcher.end() : 0;

        Matcher numberMatcher = NUMBER_PATTERN.matcher(line);
        if (!numberMatcher.find(from)) {
            return text;
        }
        int firstStart = numberMatcher.start();
        int firstEnd = numberMatcher.end();

        if (!numberMatcher.find()) {
            return text;
        }
        int secondStart = numberMatcher.start();
        int secondEnd = numberMatcher.end();

        String newLine = line.substring(0, firstStart)
                + formatCoordinate(lng) + line.substring(firstEnd, secondStart)
                + formatCoordinate(lat) + line.substring(secondEnd);
        return replaceLine(text, lineIndex, newLine);
    }

    /*============================== 内部工具 ==============================*/

    private static String getLine(String text, int lineIndex) {
        int start = lineStart(text, lineIndex);
        int end = lineEnd(text, lineIndex);
        if (start < 0 || end < 0) {
            return null;
        }
        return text.substring(start, end);
    }

    private static String replaceLine(String text, int lineIndex, String newLine) {
        int start = lineStart(text, lineIndex);
        int end = lineEnd(text, lineIndex);
        if (start < 0 || end < 0) {
            return text;
        }
        return text.substring(0, start) + newLine + text.substring(end);
    }

    private static String formatSpeed(double speed) {
        return Double.toString(speed);
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
