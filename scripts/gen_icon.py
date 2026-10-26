# -*- coding: utf-8 -*-
"""生成「看电视」新图标的各密度 PNG / WEBP 与首页 logo。
电视造型：主色 #0B57D0 外框 + 浅蓝 #E4EDFC 屏幕 + 天线 + 底座，白色圆角/圆形底。
"""
import os
from PIL import Image, ImageDraw

BLUE = (11, 87, 208, 255)        # ag_primary #0B57D0
SCREEN = (228, 237, 252, 255)    # ag_primary_bg #E4EDFC
WHITE = (255, 255, 255, 255)

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(ROOT), "app", "src", "main", "res")

def draw_tv(d, cx, cy, w, h, line_w, frame=BLUE, screen=SCREEN):
    """在 d 上以 (cx,cy) 为中心、w x h 外框绘制电视造型。"""
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    r = w * 0.16
    # 外框（主色圆角矩形）
    d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=frame)
    # 屏幕（浅蓝内缩）
    inset = line_w
    d.rounded_rectangle([x0 + inset, y0 + inset, x1 - inset, y1 - inset],
                        radius=max(1, r - inset), fill=screen)
    # 天线：从外框顶两角向中点上方汇聚（V 形）
    ax = w * 0.5
    apex_y = y0 - h * 0.30
    d.line([x0 + w * 0.08, y0 + h * 0.02, cx, apex_y], fill=frame, width=line_w)
    d.line([x1 - w * 0.08, y0 + h * 0.02, cx, apex_y], fill=frame, width=line_w)
    # 底座：屏幕下方的小横条
    stand_w = w * 0.30
    stand_h = h * 0.10
    d.rounded_rectangle([cx - stand_w / 2, y1 + h * 0.02, cx + stand_w / 2, y1 + h * 0.02 + stand_h],
                        radius=stand_h / 2, fill=frame)

def legacy_icon(size, round_bg):
    """白色底（圆角或圆形）+ 电视造型。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(size * (0.5 if round_bg else 0.22))
    if round_bg:
        d.ellipse([0, 0, size - 1, size - 1], fill=WHITE)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=WHITE)
    line_w = max(1, int(size * 0.055))
    draw_tv(d, size * 0.5, size * 0.52, size * 0.62, size * 0.42, line_w)
    return img

def logo_icon(size):
    """透明底 + 纯电视造型（首页顶栏 logo）。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    line_w = max(1, int(size * 0.06))
    draw_tv(d, size * 0.5, size * 0.52, size * 0.78, size * 0.52, line_w)
    return img

def supersample(fn, scale=4):
    """先按 scale 倍绘制再缩小，得到平滑边缘。"""
    def wrapper(size, *a, **k):
        big = fn(size * scale, *a, **k)
        return big.resize((size, size), Image.LANCZOS)
    return wrapper

sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}

def write_pngs():
    for folder, size in sizes.items():
        os.makedirs(os.path.join(RES, folder), exist_ok=True)
        icon = supersample(legacy_icon)(size, False)
        icon.save(os.path.join(RES, folder, "ic_launcher.png"))
        rnd = supersample(legacy_icon)(size, True)
        rnd.save(os.path.join(RES, folder, "ic_launcher_round.webp"), format="WEBP", quality=95)
    # 首页顶栏 logo（透明底，192px，drawable-nodpi）
    logo = supersample(logo_icon)(192)
    logo.save(os.path.join(RES, "drawable-nodpi", "ic_logo.png"))

if __name__ == "__main__":
    write_pngs()
    print("done")
