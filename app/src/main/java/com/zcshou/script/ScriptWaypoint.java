package com.zcshou.script;

/**
 * 脚本路点：一个“带移动状态的位置”。
 *
 * <p>坐标统一保存为服务层使用的 WGS-84（与 {@link com.zcshou.service.ServiceGo} 一致），
 * 界面上需要时再通过 {@link com.zcshou.utils.MapUtils} 转换成 BD-09 展示。</p>
 */
public class ScriptWaypoint {
    /** 移动状态：步行 / 跑步 / 骑行 */
    public enum Mode {
        WALK("walk", "步行", "setting_walk"),
        RUN("run", "跑步", "setting_run"),
        BIKE("bike", "骑行", "setting_bike");

        /** 脚本文件里使用的英文关键字 */
        public final String key;
        /** 界面上显示的中文名 */
        public final String label;
        /** 对应“设置”里的速度项，脚本没有写速度时用它 */
        public final String settingKey;

        Mode(String key, String label, String settingKey) {
            this.key = key;
            this.label = label;
            this.settingKey = settingKey;
        }

        /** 脚本里的关键字 / 中文名 / 首字母都能识别 */
        public static Mode parse(String text) {
            if (text == null) {
                return null;
            }

            String value = text.trim().toLowerCase(java.util.Locale.US);
            if (value.isEmpty()) {
                return null;
            }

            for (Mode mode : values()) {
                if (value.equals(mode.key) || value.equals(mode.name().toLowerCase(java.util.Locale.US))) {
                    return mode;
                }
            }

            // 中文关键字
            if (value.contains("步") || value.contains("走")) {
                return WALK;
            }
            if (value.contains("跑")) {
                return RUN;
            }
            if (value.contains("骑") || value.contains("车")) {
                return BIKE;
            }

            return null;
        }
    }

    /** 纬度（WGS-84） */
    public double lat;
    /** 经度（WGS-84） */
    public double lng;
    /** 海拔（米） */
    public double alt;
    /** 移动状态 */
    public Mode mode;
    /**
     * 速度（米/秒）。小于等于 0 表示使用当前状态在“设置”里的默认速度，
     * 也就是“只区分走 / 跑 / 骑”的简单写法。
     */
    public double speed;
    /** 到达这个点之后停留的秒数（用于模拟原地等待） */
    public int waitSeconds;

    public ScriptWaypoint(double lng, double lat, double alt, Mode mode, double speed, int waitSeconds) {
        this.lng = lng;
        this.lat = lat;
        this.alt = alt;
        this.mode = mode == null ? Mode.WALK : mode;
        this.speed = speed;
        this.waitSeconds = waitSeconds;
    }
}
