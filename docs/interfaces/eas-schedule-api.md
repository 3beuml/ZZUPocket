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
- 学校自己的两个客户端都有「含实验」开关：教务 **PC 端课表页**与豫见郑大「教学管理平台」H5。
- 2026-10-25 实测比对（同一学生第 7 周）：ZZUPocket 与豫见郑大「我的日程」**同缺**
  「人体寄生虫学 周五1-6节(7周)」「机能学实验② 周三6-8节(7周)」；PC 端勾选含实验后两条都在。
  医学生（东校区）课表里实验/见习占比极高，因此缺课最明显。
- **解决方案（2026-10-25 从用户 HAR 抓包确认）**：PC 端课表页的真实数据源不是
  micro-server，而是经典学生门户（`/student/for-std/...`）——见 2.4。
  含实验勾选在网页端是**前端过滤**（`filterActByExperiment` 按 `activity.experiment` 筛），
  数据本身就在 print-data 响应里；请求侧同时带 `hasExperiment=true` 参数。
- 已知同类缺口：micro-server course-table 实测**不含周末补课**（见 `EasClient.kt` 注释）。
- micro-server 是否也接受 `hasExperiment` 参数：**未测试**（当前方案不需要它）。

#### `lessonType` 取值域（2026-10-25 自 secondary-portal 页面内置枚举取证）

| 值 | 含义 |
|---|---|
| `theory.course` | 理论课 |
| `experiment.course` | 实验课 |
| `practice.course` | 实践课 |
| `test.course` | PBL 课 |
| `machine.course` | 上机课 |
| `design.course` | 设计课 |
| `extra.course` | 实训 |

完整分布靠 logcat `ScheduleRaw` 行观察（`logScheduleShape`）。修 issue #7 时需要把
`lessonType` 落库（`CourseSessionEntity` 加列 → `CACHE_VERSION` +1），UI 据此区分实验课样式。

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

### 2.4 经典学生门户接口（PC 端课表页真实数据源，2026-10-25 自用户 HAR 取证）

PC 端课表页（`/student/for-std/course-table`）不走 micro-server，全部在 `/student/...` 下，
鉴权靠**网页会话 Cookie**（HAR 导出已脱敏，令牌形式未验证——App 用 `Authorization` 头调用，
失败时降级，见 `EasClient.fetchPrintActivities`）：

| 接口 | 响应要点 |
|---|---|
| `GET /student/for-std/course-table/semester/{id}/print-data?semesterId={id}&hasExperiment=true` | **结构化全量排课**（App 的实验排课来源）：`studentTableVms[].activities[]`，每条带 `weekday/startUnit/endUnit/weekIndexes[]/room/building/campus/startTime/endTime/teachers[]/experiment`；`experiment=true` 即实验/见习段；**无 `date` 字段** |
| `GET /student/for-std/course-table/get-data?semesterId={id}&dataId={数据ID}&bizTypeId=2` | lessons（**文本形态**排课：`scheduleText` 如 `3,7~15(单)周 星期二 5~6节`）+ `experimentSchedules`（实测某账号为空）+ `currentWeek` + `weekIndices` |
| `GET /student/home/get-current-teach-week` | `{"currentSemester":"2026-2027-1","weekIndex":7,"isInSemester":true}` ——**服务器权威当前周** |
| `GET /student/ws/semester/get/{id}` | 完整学期记录：**`startDate`/`endDate`**（PC 页"学期起始日期"的来源） |

「含实验」勾选框的行为（自 `new-course-table.js` 逆向）：数据请求带 `hasExperiment: true`，
勾选切换时**前端过滤**（`filterActByExperiment` 按 `activity.experiment` 筛选重渲染）——
所以两次抓包的响应可以逐字节相同（2026-10-25 实测）。

App 侧的用法（`ScheduleRepository.refreshCourseTable`）：micro-server 主数据**完全优先**
（它带 `date`，是补课/调休匹配的锚），print-data 只补主数据没有的键
（`courseKey|weekday|startUnit|weekIndex`），补上的记录带 `experiment` 标志、`date=null`；
print-data 任何失败都降级为"只用主数据"。合并是纯函数（`mergePrintActivities`），有单测。

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

1. ~~含实验参数名未定~~ **已解决（2026-10-25）**：实验排课来自经典门户 print-data
   （见 2.4），App 已接入（`EasClient.fetchPrintActivities` + `mergePrintActivities`）。
   ⚠️ 剩余验证点：`Authorization` 令牌调 `/student/for-std/*` 是否通行（不行则需会话
   Cookie 方案）——失败已降级，不影响现有功能。
2. `lessonType` 仍未落库；实验/见习目前用 print-data 的 **`experiment` 布尔标志**
   落库（`course_sessions.experiment` 列，v8 迁移），网格里显示「（实验）」后缀。
3. 合并后**同格多门课会变多**（实验常与理论同时段）：周网格一格只画一门课
   （`ScheduleScreen.DayColumn` 的 `firstOrNull`），`logGridCollisions` 已统计冲突，需决定呈现策略。
4. 教室名自带楼栋前缀且可能**无分隔符**（如 `物理馆X101`）→ `tidyPlace` 前缀去重失效，
   楼名显示两遍（issue #6）——已修复（前缀剥离，仅非数字结尾楼名）。
5. `state` / `lessonType` 的完整取值分布只有日志级观察，改版时先看 logcat `ScheduleRaw`。
6. print-data 的 `startTime` 小时不补零（`8:00`），入库前统一成 `08:00`（`normalizeHhmm`）。

## 五、实测证据索引（2026-10-25）

| 项 | 方式 | 结果 |
|---|---|---|
| 第 7 周逐格比对 | ZZUPocket 截图 vs 教务 PC 课表（含实验勾选） | 缺 2 条，均为实验/见习类（issue #7） |
| 豫见郑大模块直连 | ADB 打开模块后抓进程 TLS 连接 | H5Activity → jwxt.zzu.edu.cn:443 |
| ZZU.Py 同源 | `zzupy/app/auth.py` 源码 | `APP_ID = "com.supwisdom.zzu"` |
| getWeekOfTeaching | 无 token 直调 | 200 + 「没有访问权限01」（端点存在，需 token） |
| lessonType 枚举 | secondary-portal 页面 HTML | 理论/实验/实践/PBL/上机/设计/实训 7 种取值 |
| 学校移动日程对照 | 豫见郑大「我的日程」截图 | 与 App 同缺（同源旁证） |
| **print-data 结构化全量** | 用户 HAR 抓包（`/student/for-std/.../print-data?hasExperiment=true`） | 18 条 activities，含 `experiment` 标志、`weekIndexes`；两份抓包（勾/不勾）响应逐字节相同 → 勾选是前端过滤 |
| **当前周/学期起止** | HAR：`get-current-teach-week`、`ws/semester/get/194` | `weekIndex=7`；`startDate=2026-09-07` |
| 合并实现 | `EasPrintDataTest`（夹具=真实响应切片，教师脱敏） | 330 项单测全过 |
