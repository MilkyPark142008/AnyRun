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
