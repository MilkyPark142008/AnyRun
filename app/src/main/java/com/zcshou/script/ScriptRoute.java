package com.zcshou.script;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一条脚本路线：一组按顺序执行的路点。
 */
public class ScriptRoute {
    /** 路点顺序执行完之后的处理方式 */
    public static final int END_STOP = 0;
    public static final int END_LOOP = 1;

    public String id;
    public String name;
    /** 路点顺序执行完之后是否从头继续跑 */
    public int endMode = END_STOP;
    /** 用户编辑的脚本原文，列表页直接展示、编辑页直接回填 */
    public String text = "";
    /**
     * 是否被用户在脚本编辑页手动改过。
     *
     * <p>主界面“路线选点”每次开始移动都会重新生成一条地图路线；如果这条路线已经被
     * 用户改过（改名、改路点、改速度……），就不要再覆盖它，而是另存为新脚本。</p>
     */
    public boolean edited;
    /**
     * 这条脚本里的经纬度是否为 BD-09。
     *
     * <p>null 表示“没有记录”（旧版本保存的脚本），此时沿用全局设置
     * {@link ScriptParser#KEY_SCRIPT_FROM_BD09}；一旦保存过就固定跟着脚本走，
     * 避免用户改一次全局单选就让历史脚本整体偏移几百米。</p>
     */
    public Boolean fromBd09;
    public List<ScriptWaypoint> points = new ArrayList<>();

    public ScriptRoute() {
        this.id = UUID.randomUUID().toString();
    }

    public boolean isLoop() {
        return endMode == END_LOOP;
    }

    public int size() {
        return points == null ? 0 : points.size();
    }
}
