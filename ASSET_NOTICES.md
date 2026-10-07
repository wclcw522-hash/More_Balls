# 素材来源 · Asset Notices


## 结论先行

**本项目不包含任何第三方美术资源。**

所有贴图都是**从 Minecraft 原版素材加工而来** —— 裁切、缩放、按球体法线调明暗，
没有一张是从别处下载或手绘的。


## 具体来源

| 本项目资源 | 原版来源 | 加工方式 |
|---|---|---|
| `wooden_ball.png` | `minecraft:textures/block/oak_planks.png` | 裁出圆形区域 + 球面明暗 |
| `cobblestone_ball.png` | `minecraft:textures/block/cobblestone.png` | 同上 |
| `iron_ball.png` | `minecraft:textures/block/iron_block.png` | 同上 |
| `hollow_iron_ball.png` | `minecraft:textures/item/iron_ingot.png` | 同上 |
| `gold_ball.png` | `minecraft:textures/block/gold_block.png` | 同上 |
| `amethyst_ball.png` | `minecraft:textures/block/amethyst_block.png` | 同上 |
| `copper_ball.png` | `minecraft:textures/block/copper_block.png` | 同上 |
| `hollow_copper_ball.png` | `minecraft:textures/item/copper_ingot.png` | 同上 |
| `snowball_copper_nugget.png` | `minecraft:textures/item/snowball.png` | 以白色雪球为主体，撒上铜色粒子 |
| `*_half_top / *_half_bottom` | 各自的球贴图 | 按横切保留上半 / 下半 |
| `*_quarter_1..4` | 各自的球贴图 | 按象限保留左上 / 右上 / 左下 / 右下 |
| `empty.png` | — | 16×16 全透明，仅用于分层模型的空白层 |

**「球面明暗」的做法**：把贴图当作球面投影，按每像素的法线方向与固定光源
（左上前方）做点积，乘一个 0.62–1.35 的亮度系数。所以球看起来是立体的，
但用的仍然是原版那张方块贴图。


## 为什么这样做

1. **风格统一** —— 用原版素材意味着这些球在游戏里和原版方块、物品是同一种画风
2. **不引入他人版权** —— 不下载、不借用任何第三方美术作品
3. **体积小** —— 加工是程序化做的，不是手绘一堆图


## 加工脚本

`tools/split-ball-textures.ps1`（生成半球与四分之一球的分段贴图）随源码提供。
它只做「按坐标裁切」这一件事，不引入任何外部素材。


## 关于 Minecraft 原版素材

Minecraft 的贴图版权归 Mojang Studios 所有。
**本项目并未重新分发原版贴图本身** —— 只在运行时加载游戏自带的素材并在内存中加工；
仓库里存放的是加工后的产物（不同尺寸、不同形状、经过调色的衍生图）。

如果 Mojang 认为这些衍生图的使用方式存在问题，请联系项目维护者，会立即处理。


## 没有使用的东西

为了避免误解，这里明确列出**本项目没有使用**的内容：

- 没有从任何第三方 mod 借贴图
- 没有从贴图网站下载素材
- 没有使用 AI 生成的图像
- 没有使用任何付费素材包
