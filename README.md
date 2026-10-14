# ZZUPocket

郑州大学校园助手（Android）

[![License](https://img.shields.io/github/license/3beuml/ZZUPocket.svg?style=flat)](LICENSE)
[![Release](https://img.shields.io/github/v/release/3beuml/ZZUPocket?style=flat&label=release)](../../releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3ddc84?style=flat&logo=android&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7f52ff?style=flat&logo=kotlin&logoColor=white)](#技术栈)
[![Tests](https://img.shields.io/badge/unit%20tests-223-brightgreen?style=flat)](#参与贡献)

> ⚠️ 本项目是非官方项目。
> 接口来自公开网页的协议整理与社区项目经验，**随时可能失效或被学校调整**；



## 功能特性

**学习**

- **课表**：学期 / 教学周切换，周次表头带日期（`09-30`，今天高亮）、自定义日程（可与教务课混排、撞课提醒）、
  课程总览、自定义课表背景图（含模糊与浓淡调节）、格子内容与密度可调
- **今天**：一条时间轴看"我这一天长什么样"——上课、已完成的专注；外加宿舍电量、校园卡余额与今日消费
- **专注（番茄钟）**：计时引擎 + 前台服务 + 常驻通知，标签管理，周 / 月 / 年统计与热力图
- **教室占用**：按楼筛选，每间教室哪几节有课（数据来自教务系统）

**生活**

- **电费**：照明 / 空调两套表计的余额、每日用量、电费估算、按日明细、余额趋势；
  房间号内置 59 栋楼的房间表（选楼栋 → 选房号），也支持手动填 ID
- **校园卡**：余额、日 / 周 / 月 / 年消费、流水明细与筛选
- **图书馆座位**：内嵌学校原版网页（自动用统一身份认证登录）

**桌面小组件**（RemoteViews + XML，不联网、只读本地数据）

- **下一节课**（2×2）：一节课大字 + 完整时间段 + 地点，右上角圆气泡显示"这节课以及之后还有几节"
- **今明课表**（4×2）：左列今天、右列明天，各列出还没上的课
- **电量与校园卡**（4×2）：左半照明 / 空调余额与"可用约 N 天"，右半校园卡余额与本周消费；
  两块分区可点，分别进电费页 / 校园卡页


## 下载安装

到 [Releases](../../releases) 下载最新的 `ZZUPocket-x.y.z-Pn-release.apk`，在手机上安装即可
（Android 12 / API 31 及以上）。

- 装在手机上会走系统的"未知来源应用"授权；Flyme / MIUI 这类系统还需要在
  「设置 → 应用 → 权限」里给文件管理器或浏览器放行"安装未知应用"。
- App 之间用同一套签名，所以**后续版本可以直接覆盖安装、数据不丢**。


## 首次使用

1. **登录**：更多 → 登录 / 切换账号，走学校统一身份认证（CAS）。**App 不保存密码**，只保存加密后的登录令牌。
   首次登录会让手机上的「豫见郑大」下线一次（学校统一认证机制导致），之后不会；
   建议登录后到统一认证的「安全中心」把本设备设为可信设备，以后就不需要短信验证码了。
2. **设置宿舍房间号**：电费按房间号查，每个人不一样，所以没有默认值。进「更多 → 电费」，两条路：
   - **从学校账户读取**（省事）：读一次你账号当前绑定的房间，直接填进来（只填对应那一路，
     另一路不动）。**内置房间表里没有的楼栋（例如亿达学生公寓）只能这样设**，或者手动填房间号。
   - **选校区 → 选楼栋 → 选房号**：内置房间表覆盖主校区、北校区、东校区、南校区、护理学院、
     洛阳校区六个区域（**房间号第一段就是校区**），选完自动带出照明与空调两套表计。
     空调号通常就是照明号前面加 `8`（如 `…-101` ↔ `…-8101`）。
     洛阳校区是按楼层分的，楼栋列表里会写成「1号宿舍楼 2层」这样，照自己的楼层选。

   ⚠️ **房间号规律因楼而异 —— App 已经按每栋楼各算一套，不用你手抄**：
   - 照明和空调是**两套独立电表**，余额不能互抵，两个号都得对；
   - 「空调号 = 照明号加前导 8」只是**主校区的多数派**规律：荷园 2–9 号楼是
     **照明房号 + 一位住户序号**（`302` → `3021/3022/3023`，一间房每人一个空调户，
     界面会让你选自己是哪一个）；北校区 = 房号 + 楼号×1000、南校区同理、东校区是负偏移、
     护理学院 +8000。这些规律是**用照明/空调两侧的真实房号离线算好、覆盖率核过**的
     （见 `assets/rooms.json` 里的 `acRule`），运行时只套用，不再靠猜房号后缀；
   - 数据里**确实没有**对应关系的楼栋（如松园部分楼的 `2222`/`3333` 占位号）会如实说明，
     并建议改用「在线选房间」（那是学校服务器给的确切编号），而不是让你手抄；
   - 有些楼只有一套表（如留学生公寓），只填一路即可，不会一直提示"还没设置"。
3. **离线可用**：课表、专注记录、电费历史都是本地数据，断网也能看；
   教室占用、图书馆、校园卡需要联网。

## 项目结构

```
app/src/main/java/com/zzupocket/app/
├── core/            纯逻辑与基础设施：手动依赖容器 AppGraph、加密、HTTP 客户端、
│                    错误文案、课表文本、登录输入体检、学期过滤
├── data/
│   ├── auth/        CAS 统一认证（MFA / 短信 / 令牌刷新）
│   ├── eas/         教务系统（课表、节次、教室占用）
│   ├── ecard/       一卡通（电费、校园卡流水）
│   ├── lib/         图书馆（WebView 会话与探测）
│   ├── db/          Room 实体与 DAO
│   ├── prefs/       DataStore（界面偏好、专注偏好、登录冷却）+ 加密令牌
│   ├── room/        宿舍楼房间表（assets/rooms.json）
│   ├── repo/        Repository：把接口与本地库缝在一起
│   └── work/        定时采样电量（WorkManager）
├── domain/          纯数据模型与统计口径（课表、用电、专注、校园卡、周次对齐）
├── focus/           番茄钟引擎、通知、前台服务
└── ui/              Compose 界面：today / schedule / focus / campus / library /
                     widget（桌面小组件）/ login / about / diag / theme / nav

app/src/main/res/    资源：布局（含 widget_* 小组件布局）、配色、字符串、预览图
docs/                项目文档（接口契约、实测坑、设计推演），见下方「文档」
tools/               开发脚本（如按真机几何生成小组件预览图）
```

## 技术栈

| 方面 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0 + Jetpack Compose + Material 3（含 Material You 动态取色与 8 套预设配色） |
| 依赖注入 | **手写**（`core/AppGraph.kt`）—— 依赖不多，P1 最大的风险是接口协议而不是 DI |
| 网络 | OkHttp + kotlinx.serialization（**不用 Retrofit**：接口少，且 CAS 登录的 POST 参数走 query string，裸 OkHttp 更直接） |
| 本地存储 | Room（课表 / 专注 / 电量采样 / 流水）、DataStore（偏好）、EncryptedSharedPreferences + Tink（令牌） |
| 后台 | WorkManager（每 4 小时采样一次电量）+ 前台服务（番茄钟）+ AlarmManager（小组件在上下课时刻刷新） |
| 桌面小组件 | AppWidgetProvider + RemoteViews + XML（**不用 Glance**，理由见 `docs/design/UI与登录设计.md` §2.6） |
| 后端 | **没有**。不经过任何自建中转服务器，账号凭据与数据只在设备和学校系统之间流动 |


## 隐私与边界

- **不保存密码**。登录只走学校统一身份认证，本机仅保存加密后的登录令牌；退出登录会清除。
- **数据只在本机**：课表、专注记录、电费采样、校园卡流水都存在本机数据库，
  **没有自建服务器，也不会上传**。卸载 App 会一并清掉。
- **只读接口**：不做电费充值、不做校园卡转账、**不做自动抢座**；教室占用只显示"是否被占用"。
- **不采集**：没有统计 SDK、没有崩溃上报、没有广告。
- 仓库本身**不接收**任何把账号密码、令牌、个人房间号写进代码或文档的提交。


## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/`](docs/README.md) | 文档索引 |
| [`docs/interfaces/鉴权与登录契约.md`](docs/interfaces/鉴权与登录契约.md) | CAS / 教务 / 一卡通 / 校园卡 / 图书馆的登录方式与会话形态 |
| [`docs/interfaces/接口现状核查.md`](docs/interfaces/接口现状核查.md) | 逐个接口的实测结果（含字段、单位、坑） |
| [`docs/interfaces/接口实测坑.md`](docs/interfaces/接口实测坑.md) | 金额单位、必填字段、不存在的接口 |
| [`docs/interfaces/图书馆WebView灰屏.md`](docs/interfaces/图书馆WebView灰屏.md) | 图书馆网页在 WebView 里的机制、排除过程与有效修法 |
| [`docs/design/`](docs/design/) | 可行性与开发方案、Android 接入设计、UI 与登录设计、功能与工具清单 |




## 致谢

接口协议（CAS 登录、教务 EAS、一卡通）参考 / 对齐了以下社区项目，均为 MIT 许可：

- [Illustar0/ZZU.Py](https://github.com/Illustar0/ZZU.Py) —— 豫见郑大的 Python API 封装（MIT）
- [Elykia093/ZZU-Electricity-Monitor](https://github.com/Elykia093/ZZU-Electricity-Monitor) ——
  宿舍电量监控（MIT）：**内置房间表（`assets/rooms.json`）由它的 `rooms/99.json` 精简而来**，
  定时采样节奏与低电量阈值也参考了它
- [rimrose/ZZU-Dorm-Electricity-Balance-Base](https://github.com/rimrose/ZZU-Dorm-Electricity-Balance-Base) ——
  宿舍用电记录（MIT）

桌面小组件的版式参考了 [拾光课程表](https://github.com/ShiGuangSchedule/shiguangschedule) 的实现思路
（Apache-2.0；只参考了设计，没有拷贝代码）。

## 许可证

[MIT](LICENSE) © 2026 3beuml
