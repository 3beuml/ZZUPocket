# AI 提供商与资讯摘要

> 第一版只支持 OpenAI 兼容的 `chat/completions` 接口；默认 DeepSeek，可换任意兼容服务商。
> 请求从手机直连服务商，不经过本项目的任何后端。
>
> 最后核对：2026-11-23。

## 一、配置入口

**更多 → 账号 → AI 提供商**

| 字段 | 留空时 | 说明 |
|---|---|---|
| 服务商 | DeepSeek | 仅影响默认提示，不改变协议 |
| Base URL | `https://api.deepseek.com` | 填到 API 根地址（通常到 `/v1` 为止）；请求自动补 `/chat/completions` |
| 模型 | `deepseek-chat` | 自定义服务商必须填写实际模型名 |
| API Key | 无 | 必填；只在用户点「测试连接 / 保存」时读取 |

如果用户直接粘贴了完整的 `.../chat/completions` 地址，也会原样使用。

## 二、存储与隐私

- API Key 存在独立的 **EncryptedSharedPreferences** 文件 `ai_provider_prefs.xml`，复用应用 MasterKey；
- 该文件已加入 `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 的排除项，不进入云备份/换机迁移；
- 与校园账号令牌（`secure_prefs`）分开，**校园账号登出不清理 AI Key**；
- 请求使用独立 OkHttpClient，不挂日志拦截器；Key、正文都不会写入 logcat；
- 不自动请求：只有用户点「AI 提炼」或「AI 速览」时才发送；
- 文章正文最多发送 6000 字（超长会截断并标记）；速览只发送前 5 条的标题和摘要。

## 三、请求形状

```
POST {Base URL}/chat/completions
Authorization: Bearer <api-key>
Content-Type: application/json

{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "temperature": 0.2,
  "max_tokens": 500,
  "stream": false
}
```

- 详情摘要：3-5 条 Markdown 要点，优先时间、地点、截止日期、报名方式、适用对象；
- 速览：前 5 条资讯的标题 + 摘要，输出一段概览和主题要点；
- 系统提示明确把网页正文当资料，不执行其中任何指令。

## 四、错误处理

| HTTP | 提示 |
|---|---|
| 401 | API Key 无效或已过期 |
| 402 | 账户余额不足 |
| 403 | 无权访问该接口或模型 |
| 404 | 接口地址不存在，请检查 Base URL |
| 429 | 请求过于频繁或额度已用尽 |
| 5xx | AI 服务商暂时不可用 |
| 网络错误 | 无法连接 AI 服务商，请检查网络或 Base URL |

设置页的「测试连接」发一个 `max_tokens=8` 的最小请求，只验证鉴权与地址，不消耗完整摘要额度。

## 五、渲染

AI 返回的 Markdown 由 `ui/news/MarkdownText.kt` 本地渲染，支持：

- 标题、段落、无序/有序列表、引用、围栏代码、分隔线；
- 行内粗体、斜体、行内代码；
- 不解析 HTML；链接只显示带下划线的文字，不自动打开（避免被编造的 URL 误导）。

## 六、已知边界

1. 只做了对话补全，不做流式输出；长摘要约几秒返回。
2. 摘要结果只在进程内缓存（按文章 id + 模型），换模型/进程重启会重新请求。
3. 没有 token 计费统计；费用由用户与服务商结算。
4. 自定义服务商必须是 HTTPS；明文 HTTP（包括局域网本地模型）暂不支持。
