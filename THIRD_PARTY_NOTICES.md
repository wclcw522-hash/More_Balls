# 第三方声明 · Third-Party Notices




本项目的仓库里**不包含任何第三方 mod 的代码或资源**。
下面列出的都是「编译期依赖」或「运行期可选联动」，它们各自遵守各自的许可。


## 编译期依赖

### NeoForge

- **用途**：模组加载器与 API
- **许可**：LGPL-2.1
- **是否打包进产物**：否。构建时链接，运行由玩家自己安装的 NeoForge 提供
- **地址**：https://github.com/neoforged/NeoForge

### Minecraft

- **用途**：游戏本体
- **许可**：Mojang Studios 最终用户许可协议
- **是否打包进产物**：否
- 本项目在开发时使用 NeoForge 的 ModDevGradle 拉取带补丁的游戏源码用于编译


## 运行期可选联动

以下 mod **不是必需**的。没装的时候本 mod 会**优雅降级**，功能照常、不会崩溃。

### JEI（Just Enough Items）

- **用途**：在 JEI 里展示本 mod 的配方，包括组合球的二合一 / 四合一示例
- **许可**：MIT
- **依赖方式**：`compileOnly` + 运行期用 `ModList.get().isLoaded("jei")` 判断
- **降级行为**：没有 JEI 时配方依然能合成，只是看不到配方页
- **地址**：https://github.com/mezz/JustEnoughItems

### AppleSkin

- **用途**：食物相关的额外提示
- **许可**：Unlicense
- **依赖方式**：同上
- **降级行为**：无影响
- **地址**：https://github.com/squeek502/AppleSkin

### Curios

- **用途**：让收纳袋可以挂在饰品栏（背饰 / 腰带 / 护符）
- **许可**：LGPL-3.0
- **依赖方式**：同上
- **降级行为**：没有 Curios 时收纳袋只在背包里生效，其余功能不受影响
- **地址**：https://github.com/TheIllusiveC4/Curios


## 本项目没有做的事

为了明确边界，这里列出**没有做**的事：

- **没有**把任何第三方 mod 的类打进自己的 jar
- **没有**用 Mixin 修改任何第三方 mod 的类
- **没有**覆盖或替换原版、他人 mod 的注册表内容
- **没有**硬编码他人 mod 的物品 id（一律走标签匹配）
- **没有**复制第三方 mod 的贴图或模型

唯一对原版内容的改动是**往原版物品 `minecraft:crossbow` 的物品定义里追加了本 mod 的
弹药外观分支**（`assets/minecraft/items/crossbow.json`），这是为了让弩装填本 mod 的球时
能显示对应的外观。该文件是原版文件的本 mod 版本，属于资源包覆盖，不影响其它 mod。


## 如果你想用本项目的代码

见 [LICENSE.md](LICENSE.md)。简短版：**看一下、学习、提 PR 都欢迎；
改完之后二次分发需要先说一声。**
