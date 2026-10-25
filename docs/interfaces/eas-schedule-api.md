# 本科教务（jwxt.zzu.edu.cn）课表接口协议与实现对照

> 来源：2026-09 的接口核查与 ZZU.Py 协议对齐打底；2026-10-25 排查 issue #7（东校区课表缺课）时
> 用真实会话 + 学校两端页面（PC 课表 / 豫见郑大「教学管理平台」H5）逐格比对取证补充。
> 本文与 `app/src/main/java/com/zzupocket/app/data/eas/EasClient.kt` 一一对应；
> 研究生走另一套系统（gmsstu），见 `docs/gms-schedule-api.md`。
> ⚠️ 所有结论随时可能被校方改版推翻；与代码冲突时以代码为准。

## 一、鉴权

- JWT 裸令牌：请求头 `Authorization: <userToken>`，**无 `Bearer ` 前缀**。
- 网关拒绝时响应体为空（401 + content-length 0）；401 = 令牌失效（重登有用），
  403 = 令牌有效但无权限（研究生账号在本科教务必然 403，重登无用）。
- ⚠️ `/eams-micro-server/` 与 `/eams-door/` 前缀下任何路径未认证都返回 401
  （网关在路由之前鉴权），**401 不能用来枚举/推断接口是否存在**，新接口必须抓包。

## 二、课表接口族

### 2.1 整学期课表 `GET /eams-micro-server/api/v1/lesson/student/course-table/{semesterId}`

一次请求拿全学期；每条上课记录自带 `weekIndex`（教学周号）与真实 `date`，
所以切周 / 课程详情 / 课程总览 / 今日匹配全部可以在本地算。

响应骨架（camelCase，`Envelope{result, msg, data}`，`result=0` 为成功）：

```json
{"result":0,"data":[{
  "course": {"id":0,"code":"511020-012","nameZh":"病理生理学","credits":0.0},
  "schedules": [{
    "weekday":1,"startUnit":1,"endUnit":2,"weekIndex":7,
    "date":"2026-10-19T08:00:00+08:00","startTime":"…","endTime":"…",
    "teacherName":"…","room":{"building":{"nameZh":"北2"},"nameZh":"北2_104","campus":{"nameZh":"…"}},
    "customPlace":null,"lessonType":"…","state":null
  }],
  "teacherAssignmentList":["…"],"openDepartment":{"nameZh":"…"},"stdCount":0
}]}
```

字段要点（对应 `EasClient` 的 DTO）：

| 字段 | 说明 |
|---|---|
| `weekIndex` / `weekday` / `startUnit` / `endUnit` | 周网格四元组；**可能为 0**（临时补课常只给日期） |
| `date` | ISO 日期时间，取前 10 位即可；**补课匹配的锚点** |
| `room.building` + `room.nameZh` | 教室名**自带楼栋前缀**（`北2_104`），直接拼会重复，交 `ScheduleText.tidyPlace` 规整 |
| `lessonType` | 课程类型，**服务端正常下发、本 App 目前只记日志未使用**（见下） |
| `state` | 课程状态，取值分布未知，靠 logcat `ScheduleRaw` 观察 |

无网格记录（`weekIndex=0`）的临时补课**只要带 `date` 就入库**，按日期匹配显示；
连日期都没有的记录无法落位，只能丢弃（`fetchCourseTable` 内有丢弃计数日志）。

#### ⚠️ 关键坑：默认返回 =「不含实验」视图（issue #7 根因，已解决）

- micro-server 的 course-table **不带参数时，实验 / 见习类排课不在响应里**。
- 学校自己的两个客户端都有「含实验」开关：教务 **PC 端课表页**与豫见郑大「教学管理平台」H5
  的「课表查询」（服务 `eams-student-course-table-app`）。
- 2026-10-25 实测比对（同一学生第 7 周）：ZZUPocket 与豫见郑大「我的日程」**同缺**
  「人体寄生虫学 周五1-6节(7周)」「机能学实验② 周三6-8节(7周)」；PC 端勾选含实验后两条都在。
  医学生（东校区）课表里实验/见习占比极高，因此缺课最明显。
- **✅ 最终解（2026-10-25 晚，自 eams-student-course-table-app 的 JS 逆向确认）**：
  移动端「课表查询」H5 调的就是**本接口**，只是带查询参数
  `GET /lesson/student/course-table/{semesterId}?hasExperiment=true`（勾选框默认开，
  `value:"1"`）。App 已带上该参数（`EasClient.fetchCourseTable`），实验排课随主数据一起返回，
  `lessonType=EXPERIMENT` 打实验标（`course_sessions.experiment`）。
- PC 端走的是另一套经典门户（`/student/for-std/...`，见 2.4），其「含实验」勾选在网页端是
  **前端过滤**（`filterActByExperiment`）——两端的"含实验"不是同一个开关，但数据都全。
- 已知同类缺口：course-table 实测**不含周末补课**（见 `EasClient.kt` 注释）。

#### `lessonType` 实测值域

响应里 schedules 的 `lessonType` 实测只有 **`THEORY` / `EXPERIMENT`** 两种
（logcat `ScheduleRaw` 取证；与 secondary-portal 页面那套
`theory.course`/`experiment.course`/`practice.course`… 常量枚举**不是同一套值**，注意别混）。
完整分布靠 logcat `ScheduleRaw` 行观察（`logScheduleShape`）。

### 2.2 配套接口

| 接口 | 用途 |
|---|---|
| `GET /eams-door/api/v1/calendar/get-all-semesters` | 学期列表（含 startDate/endDate/weekIndices） |
| `GET /eams-micro-server/api/v1/semester/current-semester` | 当前学期 |
| `GET /eams-micro-server/api/v1/room/system/course-units` | 节次时刻表（第 N 节 → 几点几分） |
| `/eams-micro-server/api/v1/room/place/...` | 教室占用（「教室占用」功能在用，本文不展开） |

### 2.3 教学周权威接口（备用周次源，2026-10-25 探明）

```
GET https://info.s.zzu.edu.cn/portal-api/v1/calendar/share/schedule/getWeekOfTeaching?today=<ISO日期>
头: X-Id-Token: <userToken>
```

- 无 token 实测返回 `{"code":-1,"message":"没有访问权限01"}`；周序数在 `data.data.date[0]`
  （ZZU.Py `WeekIndexModel`）。
- **研究生周次对齐的候选解**：gmsstu 不给学期起止日，现在靠「本周课表」反推 + 手动校正；
  若研究生会话能调通本接口（⚠️ 待真机验证），`GmsWeekAlign` 可弃启发式。

### 2.4 经典学生门户（PC 端课表页数据源；App 鉴权不可达，仅协议参考）

PC 端课表页（`/student/for-std/course-table`）不走 micro-server，全部在 `/student/...` 下。
**⚠️ 该层与 App 无缘（2026-10-25 真机实测）**：经典门户只认自己的网页会话——
`Authorization` 裸令牌 / `Cookie: userToken=…` / `X-Id-Token` 三种鉴权全部 302 到
`/student/login?refer=…`；带 userToken Cookie 走 `/student/login` 换会话也失败
（登录页 200 渲染表单、无跳转；本地账密 + 微信扫码，SSO 链接列表为空）。
**仅作协议参考**，实验课数据请用 2.1 的 `hasExperiment=true`：

| 接口 | 响应要点 |
|---|---|
| `GET /student/for-std/course-table/semester/{id}/print-data?semesterId={id}&hasExperiment=true` | 结构化全量排课：`studentTableVms[].activities[]`，每条带 `weekday/startUnit/endUnit/weekIndexes[]/room/building/campus/startTime/endTime/teachers[]/experiment`；无 `date` 字段 |
| `GET /student/for-std/course-table/get-data?semesterId={id}&dataId={数据ID}&bizTypeId=2` | lessons（文本形态排课 `scheduleText`）+ `experimentSchedules`（实验排课表，CS 账号实测为空）+ `currentWeek` + `weekIndices` |
| `GET /student/home/get-current-teach-week` | `{"currentSemester":"2026-2027-1","weekIndex":7,"isInSemester":true}` ——服务器权威当前周 |
| `GET /student/ws/semester/get/{id}` | 完整学期记录：**`startDate`/`endDate`**（PC 页"学期起始日期"的来源） |

「含实验」勾选框的行为（自 `new-course-table.js` 逆向）：数据请求带 `hasExperiment: true`，
勾选切换时**前端过滤**（`filterActByExperiment` 按 `activity.experiment` 筛选重渲染）——
所以两次抓包的响应可以逐字节相同（2026-10-25 实测）。

### 2.5 移动端「课表查询」H5（eams-student-course-table-app，2026-10-25 逆向）

豫见郑大「教学管理平台」里的**课表查询**（带「含实验」勾选框）不是经典门户，而是一个
独立的 uni-app H5：`https://jwxt.zzu.edu.cn/eams-student-course-table-app/index.html`
（**公开静态资源**，JS 可直接拉取分析）。要点：

- API 基址 = `https://jwxt.zzu.edu.cn/eams-micro-server/api/v1`（**与 App 完全一致**）；
- 课表 = `GET /lesson/student/course-table/{semesterId}`，参数 `hasExperiment`
  （勾选框默认开、值 `"1"`）——**issue #7 的最终解就这一个参数**；
- 其余端点：`/semester/std-semesters`、`/semester/current-semester`、`/system/course-units`、
  `/semester/current-week/{id}`（按学期的当前周）、`/lesson/student/class-mates[-exp]`；
- 鉴权 = `Authorization: <userToken>` + `X-Device-Info`（brand+model+version 拼接，非签名）
  + `X-Terminal-Info`（空串）——`eams-door/api/v1/service/list-app`（服务目录，JWT 可调）
  里每个服务带 `serviceUrl`，顺藤摸瓜找到本 H5。
- 「我的日程」模块则是另一组：eams-door `/api/v1/protal-schedule/getSchedules`（typo 是原样），
  与课表查询**数据源不同**——这就是「学校 App 里日程缺课、课表查询不缺」的原因。

## 三、学校客户端数据面（对照用）

- 豫见郑大 = Supwisdom 超级 App（`com.supwisdom.zzu`）：DCloud uni-app 壳，功能模块以远程 H5
  在 `H5Activity` 加载；APK 内无业务 API（模块资源在内部存储，无 root 读不到）。
- 「教务系统（新）」模块 = **「教学管理平台」H5，直连 jwxt.zzu.edu.cn**（实机 TLS 连接佐证）。
  即：学校移动模块的日程/课表与 ZZUPocket **同数据源**——这是「我们显示 == 学校日程显示」的原因。
- ZZU.Py（github.com/illustar0/ZZU.Py，MIT）即按豫见郑大建模：`CASClient.APP_ID = "com.supwisdom.zzu"`，
  登录走 `cas.s.zzu.edu.cn/token/password/passwordLogin`；其 EAS 客户端唯一课表接口就是 2.1 的 course-table。
- 教务 PC 门户入口：`/manager/secondary-portal`（教务部官网链接过来）；校外可达的公开 H5
  仅 `eams-room-occupy-app`（uni-app H5，教室占用）。
- 老移动校园（i郑大）是第三套：`jw.v.zzu.edu.cn/app-web`，API 前缀 `/app-ws/ws/app-service/...`，与本文无关。

## 四、已知坑位清单

1. ~~含实验缺数据~~ **已解决（2026-10-25 晚，终极解）**：course-table 带 `hasExperiment=true`
   （移动端课表查询 H5 同款参数，见 2.1/2.5）；实验记录按 `lessonType=EXPERIMENT` 打标
   （`course_sessions.experiment`，DB v8）。曾试过经典门户 print-data 补全，因会话鉴权
   不可达已**整体移除**（见 2.4 的三连 302 实测）。
2. 同格多门课只画第一门（实验常与理论同时段）：`ScheduleScreen.DayColumn` 的 `firstOrNull`，
   `logGridCollisions` 已统计冲突，呈现策略待定。
3. 教室名自带楼栋前缀且可能**无分隔符**（如 `物理馆X101`）→ `tidyPlace` 前缀去重失效，
   楼名显示两遍（issue #6）——已修复（前缀剥离，仅非数字结尾楼名）。
4. `state`（未完成/已结束）的语义未使用；改版时先看 logcat `ScheduleRaw`。
5. ⚠️ Kotlin 块注释**支持嵌套**：KDoc 里写「/路径＋星号」（如 `/student/` 后跟 `*`）会被
   当成新注释开头吞掉后面的代码（2026-10-25 踩过，编译错误定位在百行之外）。

## 五、实测证据索引（2026-10-25）

| 项 | 方式 | 结果 |
|---|---|---|
| 第 7 周逐格比对 | ZZUPocket 截图 vs 教务 PC 课表（含实验勾选） | 缺 2 条，均为实验/见习类（issue #7） |
| 豫见郑大模块直连 | ADB 打开模块后抓进程 TLS 连接 | H5Activity → jwxt.zzu.edu.cn:443 |
| ZZU.Py 同源 | `zzupy/app/auth.py` 源码 | `APP_ID = "com.supwisdom.zzu"` |
| getWeekOfTeaching | 无 token 直调 | 200 + 「没有访问权限01」（端点存在，需 token） |
| 学校移动日程对照 | 豫见郑大「我的日程」截图 | 与 App 同缺（同源旁证） |
| **print-data 结构化全量** | 用户 HAR 抓包（`/student/for-std/.../print-data?hasExperiment=true`） | 18 条 activities，含 `experiment` 标志、`weekIndexes`；两份抓包（勾/不勾）响应逐字节相同 → PC 端勾选是前端过滤 |
| **当前周/学期起止** | HAR：`get-current-teach-week`、`ws/semester/get/194` | `weekIndex=7`；`startDate=2026-09-07` |
| **经典门户鉴权墙** | 真机 App 阶梯探测（Authorization/Cookie/X-Id-Token + 登录链 6 跳） | 全部 302 `/student/login`；登录页本地账密+微信，SSO 列表空 → App 无法使用该层 |
| **课表查询 H5 逆向** | 拉取 `eams-student-course-table-app` 全部 JS | API= micro-server 同款；`getCourseTables(id,{hasExperiment})` → issue #7 终极解 |
| **服务目录** | 真机探 `eams-door/api/v1/service/list-app`（JWT+X-Device-Info） | 课表查询 serviceUrl 指向上述 H5；「我的日程」走 protal-schedule 系（数据源不同） |
| **真机回归** | 0.32.4-dev 刷新课表 + logcat | `hasExperiment=true` 下 152 条全量入库、无异常；参数与学校 H5 行为一致 |
