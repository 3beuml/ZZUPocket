---
name: zzupocket-library-webview
description: 图书馆 H5 在 WebView 里"只有灰底、内容全不画"的机制、已排除项与实测有效的修法
type: reference
scope: repo
created: 2026-09-23
updated: 2026-09-23
---

**症状**：`order.lib.zzu.edu.cn/h5/` 在 App 的 WebView 里，登录成功、路由走到 `#/mo/home`、DOM 有 128 个节点 / 13506 字符 / 11 张图、页面报错为空——但屏幕上**只有一个纯灰底（`html` 的背景色 `rgb(36,36,36)`），一个像素的内容都没有**。同一个网址在同一台手机的 Via 浏览器里完全正常。

**机制（实测）**：`html / body / #app / .MobileLayout` 的**计算高度全是 0**，而有内容的元素几何正常（卡片 `0,52 72x24`、文字 `40,326 48x18`）——它们全部 `position:absolute`，从 0 高的祖先溢出、又被 `#app{overflow:hidden}` 整片裁掉。决定性证据：在内容所在坐标 `(195,330)` 做 `elementFromPoint`，返回的是 `HTML` 而不是那个元素，说明它**确实没被渲染**。

**已经排除（都实测过，别再走一遍）**：不是深色模式（`prefers-color-scheme: dark` = false；软渲染开关也无效）、不是 rem 塌陷（根字号 16px 正常）、不是缩放（`visualViewport` 389x747 scale=1）、不是合成层/ROM 强制深色、不是网络（内容都拿到了）、不是"页面在视口高度 0 时完成首屏布局"（等视图测量出高度再 `loadUrl` 也没用）。

**有效修法**：用**像素值**把 `html / body / #app` 的高度顶成 `window.innerHeight` ——日志显示 `force-height` 之后 **600 毫秒**就 `ready:746`。注意：只触发 `resize` **无效**；`vh` / `%` 也不行。

**根因（2026-09-29 用真机 CDP 查清）：这个 WebView 里 `vh` 单位恒等于 0。**

同一个页面里逐条量出来的：

| 表达式 | 实测 | 结论 |
|---|---|---|
| `height:100vh` / `50vh` / `calc(100vh - 68px)` | **0px** | ★ 坏的就是它 |
| `height:100vw` | 389px | 正常 |
| `height:200px` | 200px | 正常 |
| `position:fixed; width:100%; height:100%` | 389×747 | 正常（视口本身没问题） |
| `window.innerHeight` / `documentElement.clientHeight` | 746 / 747 | 正常 |
| 页面自带的 `<meta name=viewport>` | `width=device-width, initial-scale=1.0, viewport-fit=cover, maximum-scale=1.0` | 声明正常 |

也就是**视口是好的，只有 `vh` 这个单位算成 0**。所以"百分比/vh 高度链塌成 0"和下面那个弹层问题**是同一个根因**；`force-height` 之所以有效，正是因为绕开了 `vh`/`%` 改用像素。

**两条实施要点**：
1. 触发时机要快：检测"**DOM 有文字但 `#app` 高度 ≈ 0**"就当场自愈（0.25 秒探测一次）。早先等到第 10 次探测才动手，白等 6 秒（打开要 10 秒，其中 3.7 秒是学校 CAS 登录往返、6 秒是自己的空等）。
2. 加载遮罩的探测循环**必须以"重新加载"为 key 重新武装**：否则成功一次后协程就结束，用户点一次刷新，遮罩重新盖上却再也没人摘下来（表现为"一直卡在正在打开"）。另加"等太久直接显示页面"的出口兜底。

**Why:** 这个问题的表象（灰屏/黑屏）会把人往深色模式、网络、渲染层上带，上面每条排除都是花了一整轮真机验证换来的。
**How to apply:** 先看 `/h5/` 这类页面在 App 里的表现，用"有文字 + `#app` 高度"两个条件判活；判定灰屏就立刻补像素高度；任何"检测后才收起的遮罩"都要有重新武装和超时出口。**页面里凡是靠 `vh` 定高的容器，一律按 0 高对待**（见下）。

## 同根因的第二个症状：「使用须知」看不到、无法预约座位（2026-09-29 修）

`#/mo/seat/appointment` 上点「立即预约」会弹出使用须知（组件 `SpaceRuleConfirm`，底部 `van-popup`），
它的样式是 `.ruleCon.ruleConMo .van-list{height:calc(100vh - 68px)}` —— 在 `vh`=0 的 WebView 里**这个列表高度就是 0**。

实测数据（真机 CDP）：`van-list` 高 **0**、`scrollHeight` 944、正文 1357 字符的 HTML **其实已经拿到了**。后果有两层：

1. 正文被裁掉 → 屏幕上表现为"**没有显示使用须知**"；
2. `van-list` 靠"滚到底"触发 `load` → 容器高 0 就**永不触发** → 组件里的 `isConfirm` 一直是 false →
   **「已知晓」按钮永久 disabled**（文案停在"请阅读完使用须知"）→ 预约流程彻底走不下去。

（同一个坑还命中「预约规则」弹窗：它用的是**内联** `height:100vh`，样式表改不到它。）

**修法**：注入 `vh → 像素` 的兜底样式（见 `LibraryScreen.kt` 的 `VH_FIX_JS`）：
把选中的几个容器按 `window.innerHeight` 换算成像素，**并且遍历样式表把所有含 `vh` 的声明改写成像素**，
再单独扫一遍**内联样式**里的 `vh`。视口变化时重算。

⚠️ 自己踩的坑：注入的 `!important` 规则**必须限定选择器范围**。
写成 `.ruleCon{height:Xpx!important}` 会把 PC 分支的 `50vh` 规则盖到移动端的 `.ruleCon.ruleConMo{height:100%}` 上，
把弹层压成半屏高（实测 `ruleCon` 从 747 变成 373）；正确写法是 `.ruleCon:not(.ruleConMo){…}`。

## 怎么调这类问题（这次一次就定位，值得复用）

1. debug 包打开 `WebView.setWebContentsDebuggingEnabled(true)`（**release 包不开**），装到真机；
2. `adb shell cat /proc/net/unix | Select-String webview_devtools` 拿到 socket 名 →
   `adb forward tcp:9444 localabstract:webview_devtools_remote_<pid>`；
3. 用 CDP（`/json/list` → WebSocket → `Runtime.evaluate`）**直接进页面量 DOM、点按钮、滚列表**。
   比"截图 + 猜"快得多，而且能**当场注入候选修复来验证因果** —— 这次就是先注入像素高度，
   看到按钮从「禁」变「可」才回头改代码，避免了又一轮瞎猜。

   注意：这台机器上 Node 的 `child_process` 被沙箱禁止（EPERM），所以 CDP 客户端只能用 `fetch` + `WebSocket`，
   **不要在 node 里 spawn adb**（adb 命令交给 PowerShell 跑）。
