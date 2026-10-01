# -*- coding: utf-8 -*-
"""生成启动图标 PNG（唯一源图：app/src/main/ic_launcher-playstore.png）。

只负责生成 PNG，不改任何 XML（XML 已由项目预先指向 @mipmap/ic_launcher_bg）。
运行一次即可，可重复执行、结果幂等：

    python tools/make_launcher_icons.py

需要 Pillow（用 DSH 自带的 Python 即已内置）。
"""

import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("缺少 Pillow：请改用 DSH 自带 Python 运行（见会话说明里的完整命令）")

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png"
RES = ROOT / "app" / "src" / "main" / "res"

# 密度 -> (传统图标边长, 自适应背景边长)。mdpi 基准为 48dp 图标 / 108dp 自适应画布
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def circle_alpha(size, supersample=4):
    """圆形透明蒙版：超采样后缩回，边缘更平滑，用于 ic_launcher_round.png"""
    big = size * supersample
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def main():
    if not SRC.is_file():
        sys.exit("找不到源图：%s" % SRC)

    raw = Image.open(SRC)
    src = raw.convert("RGBA")
    print("源图：%s  %dx%d (实际编码 %s)" % (SRC.name, src.width, src.height, raw.format))

    for name, (legacy, adaptive) in DENSITIES.items():
        out_dir = RES / ("mipmap-" + name)
        out_dir.mkdir(parents=True, exist_ok=True)

        # 传统方形图标
        square = src.resize((legacy, legacy), Image.LANCZOS)
        square.save(out_dir / "ic_launcher.png")

        # 传统圆形图标：圆外透明
        rnd = square.copy()
        rnd.putalpha(circle_alpha(legacy))
        rnd.save(out_dir / "ic_launcher_round.png")

        # 自适应图标背景层（定位针主体落在安全区内）
        src.resize((adaptive, adaptive), Image.LANCZOS).save(out_dir / "ic_launcher_bg.png")

        print("  mipmap-%s: ic_launcher %dpx / ic_launcher_round %dpx(圆形透明) / ic_launcher_bg %dpx"
              % (name, legacy, legacy, adaptive))

    print("完成：重新编译安装后即显示新图标。")


if __name__ == "__main__":
    main()
