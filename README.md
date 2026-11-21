# MobHealthTags

一个专为 **Folia** 编写的 Minecraft 服务器插件：玩家攻击生物后，临时在其头顶显示**中文名字**和**血量**，5 秒后自动还原，并兼容 **MythicMobs**。

## 特性

- **攻击触发**：玩家近战或投射物（弓箭、三叉戟等）命中生物时才显示，不再常驻扫描附近实体。
- **中文名字**：原版生物内置中文名映射（僵尸、苦力怕、末影人、监守者等）；MythicMobs 生物优先使用其 `Display` 名称。
- **血量显示**：支持 `当前/最大`、`百分比`、`两者都显示` 三种模式，并按剩余血量自动变色（绿 → 黄 → 红）。
- **自动消失**：默认 5 秒后还原生物原本的名称牌状态，不会覆盖已有自定义名称。
- **MythicMobs 兼容**：通过反射软依赖实现，未安装 MythicMobs 时也能正常运行；任意原版类型作为载体的 Mythic 生物均可触发。
- **Folia 原生支持**：名称牌写入通过生物自身的 `EntityScheduler` 执行，跟随实体跨区域迁移；全局任务只处理时间戳，不跨区域操作实体。

## 要求

- Folia 1.20.6+（用 folia-api 1.20.6 编译，理论兼容更高版本）
- Java 21+
- MythicMobs（可选，用于自定义怪物）

## 安装

1. 下载 `MobHealthTags-1.2.0.jar`。
2. 放入服务器的 `plugins/` 目录。
3. 重启服务器，生成 `plugins/MobHealthTags/config.yml`。
4. 按需修改配置，执行 `/mobhealthtags reload` 生效。

> 升级时请删除旧版本 jar，避免重复加载。

## 配置

配置文件：`plugins/MobHealthTags/config.yml`

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `display-seconds` | `5` | 名称牌显示时长（秒） |
| `update-interval` | `5` | 到期检查间隔（tick），20 tick = 1 秒 |
| `health-mode` | `both` | `absolute` / `percent` / `both` |
| `show-name` | `true` | 是否显示名字 |
| `show-health` | `true` | 是否显示血量 |
| `health-color` | `true` | 血量高低是否影响文字颜色 |
| `format` | `&f{name} &8[&8{health}&8]` | 头顶文字格式 |
| `override-custom-names` | `false` | 是否覆盖原版生物已有的自定义名字（MythicMobs 不受此限制） |
| `extra-types` | `[]` | 额外显示的 Bukkit EntityType |
| `excluded-types` | `[]` | 排除的 Bukkit EntityType |
| `mythicmobs.enabled` | `true` | 是否识别 MythicMobs 生物 |
| `mythicmobs.use-display-name` | `true` | 是否使用 MythicMobs 的 Display 作为名字 |

`format` 支持的占位符：

- `{name}` —— 生物名称
- `{health}` —— 当前血量（按模式格式化并带颜色）
- `{max_health}` —— 最大血量
- `{percent}` —— 血量百分比
- `{heart}` —— 红心符号 `❤`

颜色使用 `&` 写法，例如 `&a` 绿、`&e` 黄、`&c` 红、`&7` 灰、`&l` 粗体。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/mobhealthtags reload` | `mobhealthtags.reload`（默认 OP） | 重载配置 |

## 从源码构建

```bash
git clone https://github.com/LiuyeStudio/MobHealthTags.git
cd MobHealthTags
mvn clean package
```

产物位于 `target/MobHealthTags-1.2.0.jar`。

## 常见问题

- **MythicMobs 怪物不显示？** 确认 `mythicmobs.enabled: true`，且插件在 MythicMobs 之后加载（`plugin.yml` 已声明 `softdepend`）。
- **名字是英文？** 该实体类型不在内置中文映射中，可在源码 `ZH_NAMES` 中补充；MythicMobs 怪物请在自身配置中写中文 `Display`。
- **服务器不是 Folia？** 本插件依赖 Folia 的实体调度器，不支持 Paper/Spigot 原版核心。

## 许可

MIT License
