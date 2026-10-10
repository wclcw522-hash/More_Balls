#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「更多球」的模组封面。

作者 2026-10-10 定的最终规格：
  · 底图 = 作者提供的 MC 主世界实机截图（_cover-src/bg_world.png）
  · 五个球从左到右排满宽度：铁、石、木、金、钻石
  · 标题走立体字风格（参考 TACZ CS BOT / Modular Golems 那种厚重 3D 挤出）
  · 球与文字互不遮挡

素材：底图来自作者的截图；球用模组自己的贴图。没有自己画的像素。

用法：
  py -3 tools\build-cover.py
"""

import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

# ---------------------------------------------------------------- 路径
HERE = os.path.dirname(os.path.abspath(__file__))
ARC = os.path.dirname(HERE)
SRC = os.path.join(ARC, "_cover-src")
BALLS = os.path.join(ARC, "GitHub", "src", "main", "resources",
                     "assets", "more_balls", "textures", "item")
OUT = os.path.join(ARC, "photos", "cover.png")

W, H = 1280, 720

# 作者 2026-10-10：「打住，把字删了」——标题整体关掉，只留底图与球。
# 想再把字开回来，把这里改成 True 即可（标题那套参数都还在）。
DRAW_TITLE = False

FONT_ZH = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_EN = r"C:\Windows\Fonts\arialbd.ttf"

# 布局常量（想调直接改这一块）
BALL_SIZE = 280          # 球的边长（横向间距 256 -> 相邻球重叠 24px）
CROSSBOW_SIZE = 400      # 16 的整数倍（16 x 25）—— 缩放必须走整数倍，否则像素会碎
CROSSBOW_CY = 300        # 弩的垂直中心 —— 单独给，不受球的 y 影响

# 两个悬空的球（作者 2026-10-10 指定，**不要影子**）：
#   · 木球     —— 铁球(x=128) 与石球(x=384) 之间、偏上偏右
#   · 紫水晶球 —— 右边关于画面中线(x=640) 的对称位置
# 296 与 984 关于 640 对称；296 也正好落在铁球(128)与石球(384)的正中偏右。
FLOAT_BALL_SIZE = BALL_SIZE   # 作者 2026-10-10：这两个球和下边那排一样大
# 两个悬浮球的**中心高度** = 弩臂下缘（作者 2026-10-10：「中心和弩臂下缘水平」）。
#
# 这个数是从弩图里量出来的，不是拍的：
#   弩裁紧后 460x459，按行的棕色像素统计 —— 弩臂（棕色，只在左右两侧）占 y 75~174，
#   从 y=175 起棕色只剩中间那一列（弩身），所以「弩臂下缘」在弩图内是 y=174。
#   弩在画布上的顶边 = CROSSBOW_CY - 460/2 = 70，
#   于是画布坐标下的弩臂下缘 = 70 + 174 = 244。
FLOAT_BALL_CY = 274       # 弩臂下缘 244，再往下一点点
FLOAT_BALLS = [
    # 铁球 x=128、石球 x=384 -> 正中 256，再往右偏 40
    ("wooden_ball.png", 250, FLOAT_BALL_CY),
    # 关于画面中线 640 的对称点：1280 - 250 = 1030
    ("amethyst_ball.png", 1030, FLOAT_BALL_CY),
]
BALL_RISE = 40           # 人字形坡度：每往外一层降低多少
BALL_CY = 478            # 中间那颗（人字形最高点）的球心 y
BALL_DROP = 40           # 最外侧两颗再额外压低的量
TITLE_SIZE = 190         # 「更多球」字号
TITLE_CY = 96            # 标题中心 y
TITLE_DEPTH = 40         # 3D 挤出层数 —— 厚，才有「一大块石头」的分量

SUB_SIZE = 46            # 「More_Balls」字号
SUB_CY = 288             # 副标题中心 y
                         # 主标题底边 = 106+95+16 = 217
                         # 副标题顶边 236 / 底边 292  -> 与主标题留 19px


def load_ball(name):
    return Image.open(os.path.join(BALLS, name)).convert("RGBA")


def load_crossbow(size=CROSSBOW_SIZE):
    """上膛了钻石球的弩，竖起来并斜 45°。

    三步，顺序很关键：

      1. 先按**整数倍**放大（NEAREST）—— 每个原始像素变成方正的色块
      2. 再旋转（NEAREST）—— 色块整体转过角度，块还是块
      3. **按不透明像素裁紧** —— 这一步是为了「居中」

    为什么必须裁紧：旋转用 expand=True 撑出的画布是 400*sqrt(2) ≈ 566 见方，
    但弩本身只占其中一部分，四周全是透明。
    按画布中心去摆，弩看着就是偏的（作者反馈「使劲往上直到它正确居中」就是这个原因）。
    裁紧之后，图的中心 = 弩的中心，居中才有意义。

    PIL 的 rotate 角度**逆时针为正**。贴图原本朝左（弩臂在左、球压在左上），
    -45 表示顺时针 45°，让弩臂抬起来朝上偏左。
    """
    src = Image.open(os.path.join(BALLS, "crossbow_ball_diamond_ball.png")).convert("RGBA")

    scale = max(1, int(round(size / src.width)))
    big = src.resize((src.width * scale, src.height * scale), Image.NEAREST)
    turned = big.rotate(-45, expand=True, resample=Image.NEAREST)

    box = turned.getbbox()
    if box:
        turned = turned.crop(box)
    return turned

def fit_cover(img, size):
    """等比缩放到铺满，再居中裁剪 —— 相当于 CSS 的 background-size: cover。"""
    tw, th = size
    scale = max(tw / img.width, th / img.height)
    nw, nh = int(round(img.width * scale)), int(round(img.height * scale))
    img = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - tw) // 2
    top = (nh - th) // 2
    return img.crop((left, top, left + tw, top + th))


# ---------------------------------------------------------------- (1) 底图
def build_background():
    bg = Image.open(os.path.join(SRC, "bg_world.png")).convert("RGB")
    bg = fit_cover(bg, (W, H))
    # 底部压一层很淡的暗色，让球和字在草地上更清楚（只是渐变，不是文本框）
    veil = Image.new("L", (W, H), 0)
    vd = ImageDraw.Draw(veil)
    for y in range(int(H * 0.42), H):
        a = int(70 * ((y - H * 0.42) / (H * 0.58)))
        vd.line([(0, y), (W, y)], fill=a)
    dark = Image.new("RGB", (W, H), (12, 22, 14))
    return Image.composite(dark, bg, veil).convert("RGBA")


# ---------------------------------------------------------------- (2) 五个球
def add_balls(canvas):
    """五个位置，从左到右：

        铁球 ｜ 石球 ｜ 竖着的弩（上膛钻石球） ｜ 金球 ｜ 铜球

    作者 2026-10-10 的改动：
      · 左1（铁）、左2（石）、右2（金）三个位置的球不动
      · 右1（最右）原来是钻石球 -> 改成铜球
      · 中间的**木球**换成一把**竖着朝上的、上膛钻石球的弩**
        （钻石球没消失，它就在弩上）
    """
    lineup = [
        ("iron_ball.png", "ball"),
        ("cobblestone_ball.png", "ball"),
        (None, "crossbow"),                       # 中间：弩
        ("gold_ball.png", "ball"),
        ("copper_ball.png", "ball"),              # 最右：铜球
    ]

    step = W / len(lineup)
    centers = [step * (i + 0.5) for i in range(len(lineup))]

    mid = (len(lineup) - 1) / 2
    heights = []
    for i in range(len(lineup)):
        dist = abs(i - mid)
        y = BALL_CY + dist * BALL_RISE
        if dist == mid:
            y += BALL_DROP
        heights.append(y)

    for (name, kind), cx, cy in zip(lineup, centers, heights):
        if kind == "crossbow":
            img = load_crossbow()
            size = max(img.width, img.height)   # 用裁紧后的真实尺寸定位
            cy = CROSSBOW_CY                     # 单独给弩一个垂直位置
        else:
            img = load_ball(name).resize((BALL_SIZE, BALL_SIZE), Image.NEAREST)
            size = BALL_SIZE

        left = int(round(cx - size / 2))
        top = int(round(cy - size / 2))

        # 投影：椭圆，落在物体正下方。
        # ⚠️ 弩**不画**投影（作者 2026-10-10：「弩影子删了」）——
        #    它是悬在空中的上膛弩，不是落在地上的东西。
        if kind != "crossbow":
            sh_h = size // 3
            shadow = Image.new("RGBA", (size, sh_h), (0, 0, 0, 0))
            ImageDraw.Draw(shadow).ellipse(
                [size // 10, sh_h // 5, size - size // 10, sh_h - sh_h // 6],
                fill=(0, 0, 0, 112))
            shadow = shadow.filter(ImageFilter.GaussianBlur(6))
            canvas.paste(shadow, (left, int(round(cy + size / 2 - sh_h / 2))), shadow)

        canvas.paste(img, (left, top), img)

    # ---- 两个悬空的球：**不画影子**
    for name, cx, cy in FLOAT_BALLS:
        img = load_ball(name).resize((FLOAT_BALL_SIZE, FLOAT_BALL_SIZE), Image.NEAREST)
        canvas.paste(img,
                     (int(round(cx - FLOAT_BALL_SIZE / 2)),
                      int(round(cy - FLOAT_BALL_SIZE / 2))),
                     img)

# ---------------------------------------------------------------- (3) 立体字
def make_tex(name, block):
    """把 16x16 的方块贴图平铺、放大成一张整图 —— 当字面纹理用。

    ⚠️ 纹理是**整张铺满画面**的，不是每个字单独一张 ——
    这样贴到字面上之后，纹理在字与字之间是连贯的，像真的从一块石头上凿出来的。
    """
    src = Image.open(os.path.join(SRC, name)).convert("RGB")
    big = src.resize((block, block), Image.NEAREST)
    tex = Image.new("RGB", (W, H))
    for y in range(0, H, block):
        for x in range(0, W, block):
            tex.paste(big, (x, y))
    return tex


def shade(tex, factor):
    """把纹理整体调亮或调暗。"""
    a = np.array(tex).astype(float) * factor
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def flatten(tex, amount=0.42):
    """把纹理的**对比度压平**。

    ⚠️ 这一步是必须的。原版圆石贴图的亮度跨度是 82~181，对比度极高；
    汉字的笔画只有二十来像素宽，这么强的纹理直接铺上去会变成噪点，
    字会被「吃掉」——第一版做出来整行字糊成一团。

    压平之后纹理还在（能看出石头的颗粒），但不再盖过字形。
    """
    a = np.array(tex).astype(float)
    m = a.mean()
    return Image.fromarray(np.clip(m + (a - m) * amount, 0, 255).astype(np.uint8))


def text_mask(size, xy, text, font, stroke, anchor="mm", spacing=0):
    """画一张只有文字形状的遮罩（含描边宽度）。

    `spacing > 0` 时改成**逐字绘制**，手动拉开字距。

    为什么需要：汉字在字体里是紧排的，「更」「多」这类字形本身就有往外伸的笔画，
    一加粗描边就会连成一片、认不出是几个字 —— 第一版做出来整行像个「更每球」。
    逐字排版才能把间距控住。
    """
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    if spacing <= 0 or len(text) <= 1:
        d.text(xy, text, font=font, fill=255,
               stroke_width=stroke, stroke_fill=255, anchor=anchor)
        return m

    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = xy[0] - total / 2.0
    for ch, w in zip(text, widths):
        d.text((x + w / 2.0, xy[1]), ch, font=font, fill=255,
               stroke_width=stroke, stroke_fill=255, anchor=anchor)
        x += w + spacing
    return m

def textured_text(canvas, xy, text, font, tex, outline,
                  depth=16, stroke=10, face=1.06, side_lo=0.30, side_hi=0.62,
                  highlight=0.20, flat=0.42, anchor="mm", spacing=0):
    """带方块纹理的立体字（照 BETTER MINECRAFT / PROMINENCE 那种做法）。

    层次，由下往上：
      1. 立体侧面 —— 逐层向下偏移的文字遮罩，贴同一张纹理的**暗化**版本，
         越靠下的层越暗。叠起来就是有厚度的「凿出来」的侧面。
      2. 描边 —— 比字面再宽一圈的纯色遮罩，压在最上层之前。
      3. 字面 —— 贴原亮度（或略提亮）的纹理。
      4. 顶部高光 —— 一条很淡的白色遮罩，做出被光打到的感觉。
    """
    flat_tex = flatten(tex, flat)

    # ---- 1. 侧面
    for i in range(depth, 0, -1):
        t = i / depth                          # 1 = 最底, 0 = 最上
        f = side_lo + (side_hi - side_lo) * (1 - t)
        m = text_mask(canvas.size, (xy[0], xy[1] + i), text, font, stroke, anchor, spacing)
        canvas.paste(shade(flat_tex, f), (0, 0), m)

    # ---- 2. 每个笔画外面那圈**明确的黑边**
    #
    # 走 text_mask 而不是直接 ImageDraw.text，一是为了跟着字距排，
    # 二是把黑边加宽到 stroke+9 —— 以前只 +4，加上侧面也是暗色，
    # 两者糊在一起，看起来像是「字没有边」。
    om = text_mask(canvas.size, xy, text, font, stroke + 9, anchor, spacing)
    canvas.paste(Image.new("RGB", canvas.size, outline), (0, 0), om)

    # ---- 3. 字面（纹理）
    fm = text_mask(canvas.size, xy, text, font, stroke, anchor, spacing)
    canvas.paste(shade(flat_tex, face), (0, 0), fm)

    # ---- 4. 顶部高光
    if highlight > 0:
        hm = text_mask(canvas.size, (xy[0], xy[1] - max(2, stroke // 3)),
                       text, font, 0, anchor)
        hm = hm.filter(ImageFilter.GaussianBlur(2))
        ha = np.array(hm).astype(float) * highlight
        hm = Image.fromarray(np.clip(ha, 0, 255).astype(np.uint8))
        canvas.paste(Image.new("RGB", canvas.size, (255, 255, 255)), (0, 0), hm)

def add_title(canvas):
    d = ImageDraw.Draw(canvas)

    # 字面纹理：原版圆石贴图，放大到 34px 一块（约等于 2 倍方块大小，看得清纹理）
    # 纹理块用 16（贴图原始尺寸，1:1）—— 太大块会把笔画切碎
    cobble = make_tex("cobblestone.png", 64)

    zh = "更多球"
    f_zh = ImageFont.truetype(FONT_ZH, TITLE_SIZE)

    # 投影
    s = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(s).text((W // 2 + 8, TITLE_CY + 26), zh, font=f_zh,
                           fill=(0, 0, 0, 150), stroke_width=10,
                           stroke_fill=(0, 0, 0, 150), anchor="mm")
    s = s.filter(ImageFilter.GaussianBlur(9))
    canvas.alpha_composite(s)

    textured_text(
        canvas, (W // 2, TITLE_CY), zh, f_zh, cobble,
        outline=(0, 0, 0),
        depth=TITLE_DEPTH, stroke=10, spacing=26,
        face=1.12,              # 只轻微提亮 —— 保留圆石本来的灰
        side_lo=0.42, side_hi=0.78,   # 侧面提亮 —— 要和黑边拉开，否则看不出笔画有边
        highlight=0.30,
        flat=0.92,              # 几乎不压 —— 纹理要放大后依然清晰
    )

    # ---- 英文副标题：同一张纹理，整体压暗，拉开层次
    en = "More_Balls"
    f_en = ImageFont.truetype(FONT_EN, SUB_SIZE)
    s2 = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(s2).text((W // 2 + 4, SUB_CY + 12), en, font=f_en,
                            fill=(0, 0, 0, 140), stroke_width=8,
                            stroke_fill=(0, 0, 0, 140), anchor="mm")
    s2 = s2.filter(ImageFilter.GaussianBlur(5))
    canvas.alpha_composite(s2)

    textured_text(
        canvas, (W // 2, SUB_CY), en, f_en, cobble,
        outline=(0, 0, 0),
        depth=16, stroke=6, spacing=3,
        face=1.06,
        side_lo=0.44, side_hi=0.76,
        highlight=0.20,
        flat=0.90,
    )

def main():
    canvas = build_background()
    add_balls(canvas)
    if DRAW_TITLE:
        add_title(canvas)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    canvas.convert("RGB").save(OUT, "PNG")
    jpg = os.path.join(os.path.dirname(OUT), "cover.jpg")
    canvas.convert("RGB").save(jpg, "JPEG", quality=94, subsampling=0)
    print("已生成:", OUT)
    print("已生成:", jpg)


if __name__ == "__main__":
    main()
