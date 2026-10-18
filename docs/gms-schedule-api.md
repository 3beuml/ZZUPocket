# 研究生教务（gmsstu.zzu.edu.cn）课表接口协议与解析实现

> 来源：ZZUPocket issue #2（研究生账号把本科教务当成唯一教务菜单的「登录态失效」误报）。
> 2026-10-17 由真实研究生账号在外网（家庭宽带）实测取证，全部接口经当次会话真机复跑确认；
> 2026-10-18 复测补充了会话 Cookie 的路径与 guid 来源（见下）。
> 本文与 `app/src/main/java/com/zzupocket/app/data/gms/` 的实现一一对应；
> `tools/gms_probe.py` 是可在本机复跑整条链路的探针脚本（仅标准库）。

## 与本科教务（EAS）的差异总览

| | 本科 EAS（jwxt.zzu.edu.cn） | 研究生 gmsstu |
|---|---|---|
| 鉴权 | JWT 裸令牌 `Authorization: <userToken>` | CAS 网页会话 Cookie（/stu 302 → cas.s.zzu.edu.cn → 302 带 ticket 回） |
| 业务 URL | 明文 REST 路径 | `/stu/{base64(路由)}` 包装（见下） |
| 课表形状 | 每条记录自带 `weekIndex`（单周） + 日期 | **周区间**（如 10-17周） + 节次单值、无日期 |
| 学期起止日 | 提供 startDate/endDate | **不提供**（kbcx_drop 只有 value/text/selected） |
| 节次时刻 | 提供 /room/system/course-units | **不提供**（页面只有「第N节」） |

## 一、鉴权与路由（复现这两条就等于系统接入）

1. **外网可直连**：校外（家庭宽带）上 `https://gmsstu.zzu.edu.cn/stu/` 可直达，
   登录跳 `https://cas.s.zzu.edu.cn/cas/a/login?service=https%3A%2F%2Fgmsstu.zzu.edu.cn%2Fstu%2Fcas`，
   完成 CAS 网页登录后 302 带回 ticket，gmsstu 落一组会话 Cookie。
2. **接口只认 Cookie**（同一会话实测：把 Cookie 挖掉（fetch `credentials:'omit'` 重发同一 loadKb）
   会直接被 302 回 CAS；保持 Cookie 时返回 200 + rows）。**没有**可供替换的 JWT/Authorization。
   - 会话 Cookie 名为 `cn_com_southsoft_gmis_stu`，`Path=/stu`，`HttpOnly`。
     取会话必须用 `https://gmsstu.zzu.edu.cn/stu/`；用根路径
     `https://gmsstu.zzu.edu.cn` 调 `CookieManager.getCookie` 取不到（2026-10-18 实测）。
   - **只带这一枚 Cookie 就能调通**学期/整学期/本周接口；CAS 侧的
     `SESSION`/`TGC` 不是必需的（同一会话下 gms-only 与 cas+gms 返回完全一致）。
3. **业务路由是 base64 包装**：
   ```
   /stu/{b64}   b64 = 标准 base64( "{AppKey}{模块路径}#{session-guid}" )，'=' → '_'
                AppKey 固定 "MyHeartWillGoOn"；session-guid 会话级随机
                （实测整个会话内不变）
   ```
   - `session-guid` **就是会话 Cookie `cn_com_southsoft_gmis_stu` 的值**（36 字符 UUID）。
     2026-10-18 实测：登录后页面发出的 12 条 `/stu/{b64}` 请求解出的 guid 与 Cookie 值
     逐字相同；当前 `/stu/`、`/stu/index` 的 HTML 里已经没有 `#uuid`，HTML 正则仅作旧版兜底。
   - 实测样例（guid 代占为 deadbeef-…）与逐字节校验在 Kotlin 侧
     `GmsClient.encodeRoute(modulePath, guid)`，并有单元测试覆盖。
4. **注意**：脱离正常会话上下文对同一 URL 直接整页 GET（不经菜单/iframe 链），
   偶发 `{"code":500,"msg":"系统异常"}`；复现请保持登录 Cookie 并按正常页面流程走。
   这在 Android 端不构成问题（本就全程带 Cookie）。

## 二、数据接口

以下 URL 均以 `M $GMS_BASE/stu/{encode_route(modulePath)}` 表示，请求头（实测出现过的字段）：

```
X-Requested-With: XMLHttpRequest
Accept: application/json, text/javascript, */*; q=0.01
Content-Type: application/x-www-form-urlencoded; charset=UTF-8   （POST）
Cookie: <CAS 会话 Cookie>       ← 由 CookieJar 自动带上，不落盘不打印
```

### 2.1 学期列表 `pygl/xskbcx/kbcx_drop`（GET，无参数）

简化实测响应（无任何个人字段）：

```json
{"termcode":[
  {"value":"20261","text":"2026-2027第一学期","selected":true},
  {"value":"20262","text":"2026-2027第二学期","selected":false},
  ... (同构，覆盖 2015-16 至 2026-27，另有一项 {"value":"19001","text":"19001"})
]}
```

`selected: true` 即当前学期。页面端 jQuery 用 `cache:false` 会额外加 `_=<毫秒时间戳>`，服务端忽略。

### 2.2 整学期课表 `pygl/xskbcx/loadKb`（POST，表单）

```
kblx=xs&termcode=20261      （termcode 用 2.1 的 value）
```

实测响应骨架（此处为节流展示，实际 11 行、一节一行；教师姓名已脱敏为 占位甲/乙/丙/丁/戊）：

```json
{"rows":[
  {"id":null,"transientData":{},"attachments":null,"jcid":"1","mc":"第1节","sjbz":null,
   "z1":"新型传感技术及应用135505702_001[10-17周]占位甲[北1301]",
   "z2":null,
   "z3":"计量科学与技术135505602_002[10-17周]占位乙[北1303]",
   "z4":null,"z5":null,"z6":null,"z7":null,"xq":null,"jsxm":null,"xkfs":"2"},
  {"id":null,"transientData":{},"attachments":null,"jcid":"2","mc":"第2节","sjbz":null,
   "z1":"...（同第1节结构，同班同周）","z2":null,"z3":"...","xq":null,"jsxm":null,"xkfs":"2"},
  ... 共11行：jcid=1..11 
  最后一行 {"jcid":"99","mc":"无节次","z1":null, ...} 是占位行（解析时跳过）
]}
```

字段约定：
- `jcid`：节次编号（1..11；第10/11节当前学期无课时为 null；`99`=无节次占位）；
- `mc`：节次名（「第1节」…；占用的是公共大课的编号，仅供显示）；
- `z1..z7`：周一~周日，一条**一节课单课**（同格不会重排多门课）；
- `jsxm`（教师姓名独立字段）实测恒为 null，教师名实际内联在 z 文本里；
- `xkfs`：选课方式码，实测 2（正常）；页面对含 `##` 前缀的单元格按选课式渲染。

单元格（z?）文本解析规则（两种形态并存，全被解析覆盖，见测试）：

```
① 课程名{课程号}_{班次}[周次]教师[教室]      例：新型传感技术及应用135505702_001[10-17周]占位甲[北1301]
② 课程名(班级名)[周次]教师[教室]             例：数值分析主校区数值分析4班[2-17周]占位戊[北1204]
   ／自然辩证法概论主校区政治20班[7-10周]占位丙[南4201]
```

- 周次段 = 第一个含「周」字的 `[]`；最后一个 `[]` 是教室；之间是教师；
- 无课程号的课以「主校区/南校区/北校区/东校区」关键词切课程名与班级名；
- 周次规则同样匹配 `10-17周` 与 `7-10周`、`X周` 形式；`周[连续]` 中的 `[连续]` 是网页样式标记；
  `第7节,第8节` 等节次尾巴被忽略。

### 2.3 本周课表 `pygl/xskbcx/bzkb_load`（POST，空表单）

服务端按“当前周”返回，实测结构：

```json
{"bzkb":[
  {"zh":"1","bzkb":[{"KCMC":"数值分析","BJMC":"主校区数值分析4班","DZ":"北1204","ZS":"2-17周[连续],第7节,第8节","ZH":"1"}]},
  {"zh":"2","bzkb":[]}, ... {"zh":"7","bzkb":[{"KCMC":"研究生学术论文写作指导","BJMC":"135606501_002","DZ":"北4101","ZS":"2-5周[连续],第5节,第6节,第7节,第8节","ZH":"7"}]}
]}
```

- `zh`：星期（1..7）；外层 `bzkb` 每天一个数组；条目：`KCMC` 课程名 / `BJMC` 班级（注意：
  有些课这里实际放的是**课程号_班次**（如学术写作课）——字段语义有坑，使用前先判断形态）；
- `ZS`：`周次[标记],第X节,...`；`DZ`：教室。

### 2.4 其他导出接口（备查，App 暂未使用）

- 导出：`pygl/xskbcx/exportExcel1`（POST，主体为列定义 JSON 数组，query 带 `kblx/termcode`），
  随后 `pygl/xskbcx/downloadExcel?fileName=..&path=..`；
- 外壳杂项：`<AppKey>index/position`（POST，body `p=%2Fstu%2F…`，用于顶部学期条）、
  `<AppKey>index/addMenuLs?...`（菜单使用记录）。

## 三、解析实现与测试

- `GmsScheduleParser.parseCell / parseWeeks`：纯函数，单测在
  `app/src/test/java/com/zzupocket/app/data/gms/GmsScheduleParserTest.kt`，
  夹具 = 本文 2.2 的 11 行真实响应（教师脱敏）；
- `GmsClient`(data/gms/GmsClient.kt)：
  - `fetchTerms()`（2.1）、`fetchTermTable(termcode)`（2.2，返回已解析的 `GmsLesson`）、
    `fetchWeekTable()`（2.3）、`rowsToLessons()`、`expandWeeks()`；
  - `login(username, password)`：走 CAS 网页链（`/cas/a/login` 表单，密码 RSA PKCS#1 v1.5，
    公钥来自 `https://cas.s.zzu.edu.cn/cas/jwt/publicKey`，加密纯 base64，失败退 App JWT 同款
    `__RSA__` 前缀重试；默认表单带 `execution/_eventId=submit/rememberMe/指纹空值`）。
    ⚠️ **标记为实验性**：实测会话是浏览器侧完成的，App 链路的登录字段还没有真机复跑过，
    建议带首跑者一次观察 `tools/gms_probe.py` 的输出再定稿。
  - OkHttp 侧要求：**每个 Gms 实例用独立 OkHttpClient + 持久化能力的 CookieJar +
    followRedirects=true**（与 `HttpClients.eas` 那套「不跟跳」相反），因为登录链需要跨主机
    302 自动回service。
- `expandWeeks()`：把周区间展开成与本科周网格一致的「每周一条」，可直接喂给
  `CourseSessionEntity`（`sessionIdOf(...)`）。

## 四、实测证据（2026-10-17）

| 项 | 实测方式 | 结果 |
|---|---|---|
| kbcx_drop | 同会话内以页面自身 jQuery 复刻 GET | 200，25 项学期，20261 selected |
| loadKb | 同上 POST `kblx=xs&termcode=20261` | 200，11 行，4 门课 18 条记录 |
| bzkb_load | 同上 POST 空 body | 200，7 天结构，当周 2 门课 |
| 鉴权契约 | 同一 loadKb 以 `credentials:'omit'` 重发 | 服务端 302 回 CAS（fetch 跨域中止），有 Cookie 200 |
| 渲染一致性 | 网页实际渲染表格 vs 解析输出 | 8 行×2 大节结构一致（parse 后归一星期/节次/周次完全可比） |
| 路由编码 | base64(b64) 逐字节比对 | 与实测字符串完全一致（`= → '_`） |
| 外网可达 | 校外（家庭宽带）直接打开 | 可达；CAS web 登录正常 |

## 五、接入建议（下接 App 的课表仓库/UI）

1. **账号路由**：在「更多 → 登录状态」露出「研究生课表源」开关，或在首次进入课表页
   检测账号是否在本科教务（issue #2 中作者已实现）返回 403，则尝试 gmsstu 数据源。
2. **学期选择**：用 `fetchTerms()` 替代本科的学期接口；值类型为字符串（如 `20261`）。
3. **周网格**：`fetchTermTable(termcode)` → `expandWeeks()` → `CourseSessionEntity`（`source = "gms"`）。
4. **节次时刻缺失**：gmsstu 无节次时刻表。第一版可以按「第N节」显示，或用本科的
   `/room/system/course-units`（第 N 节时间基本一致）当推断参考 —— 但这属于作者定夺的 UX。
5. **本周/今日**：直接用 `fetchWeekTable()`（ 服务端自行按当前周返回），比本科端靠周推日更直接。

## 六、已知坑位/待真机复验证清单

1. `login(...)` 的表单链路（网页 CAS）**未真机复跑**；如触发验证码/MFA 会失败，
   报错体已带出（`AppError.BadCredentials` 消息含 CAS 页面文本前 300 字符）。
2. `session-guid` 的生命周期以「会话」为单位，`login()` 后再取即可；跨 App 冷启动
   CookieJar 失效时走 `ensureSession()==false` → 引导重登。
3. 周次区间的写法 range 只覆盖「X-Y周」「X周」两种实测形态；若学校改版出现
   `1,2,3周` 之类逗号列表，`parseWeeks` 需要加分支（当前实现会返回 null，调用方
   按「无周网格」处理，不会弄坏整表）。
4. gmsstu 无学期起止日期、无节次时刻：今日页与周对齐器需要作者决定采用
   本科侧一致策略（如用 `WeekAlignment` + 配置学期首日）或仅按“本周”视图展示。
