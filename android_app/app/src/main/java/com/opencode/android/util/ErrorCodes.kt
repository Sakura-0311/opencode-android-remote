package com.opencode.android.util

/**
 * v2.5: 错误码 →（标题，原因，建议）映射表。
 * 隧道类文案复用 TunnelDiagnosticsHelper 的 Cloudflare / SakuraFrp 排障口径。
 */
data class ErrorInfo(
    val title: String,
    val reason: String,
    val suggestion: String
)

object ErrorCodes {

    private val TABLE: Map<String, ErrorInfo> = mapOf(
        "AUTH_FAILED" to ErrorInfo(
            "鉴权失败",
            "Relay 拒绝了本次鉴权（密钥错误、被撤销或已过期）。",
            "重新扫码配对；若刚被管理员撤销，需对方重新授权。"
        ),
        "DEVICE_REVOKED" to ErrorInfo(
            "设备已被撤销",
            "该设备已被配对方撤销，连接已断开且不会自动重连。",
            "联系配对方重新配对，或在设备管理中确认。"
        ),
        "RESYNC_REQUIRED" to ErrorInfo(
            "需要重新同步",
            "断线期间错过的消息超出补发窗口。",
            "应用会自动请求全量同步；若持续出现请检查网络稳定性。"
        ),
        "PROTOCOL_MISMATCH" to ErrorInfo(
            "协议版本不匹配",
            "客户端与服务端协议版本不一致（v3 需要配套的 relay/agent）。",
            "将电脑端 agent.py 与 relay_server 一起升级到 v3.0 后再连接。"
        ),
        "DESKTOP_OFFLINE" to ErrorInfo(
            "Desktop 不在线",
            "中继已连通，但电脑端 Agent 未上线。",
            "确认电脑端 agent.py 正在运行且网络可达 Relay。"
        ),
        "OPENCODE_UNREACHABLE" to ErrorInfo(
            "OpenCode 服务不可达",
            "电脑端 Agent 无法连上本地 OpenCode 服务。",
            "检查电脑上 `opencode serve` 是否运行、端口是否一致。"
        ),
        "PATH_NOT_ALLOWED" to ErrorInfo(
            "路径越界被拒绝",
            "请求的文件路径超出允许的工作区范围，已被沙盒拦截。",
            "只访问工作区内的文件，不要使用 .. 或绝对路径。"
        ),
        "SEND_UNCONFIRMED" to ErrorInfo(
            "消息发送未确认",
            "弱网下发送超时，服务端是否收到不确定，未自动重发。",
            "检查会话中是否已存在该消息，确认没有再手动重发。"
        ),
        "SSE_RETRY_EXHAUSTED" to ErrorInfo(
            "云端重连失败",
            "云端 SSE 断线后多次重连仍失败，已停止自动重试。",
            "检查手机网络与云端地址，稍后手动重连。"
        ),
        "CREATE_SESSION_FAILED" to ErrorInfo(
            "创建会话失败",
            "OpenCode 服务创建会话失败。",
            "检查 OpenCode 服务状态与工作区路径是否存在。"
        ),
        "SECURE_STORAGE_UNAVAILABLE" to ErrorInfo(
            "安全存储不可用",
            "加密存储初始化失败，凭据未保存。",
            "重启应用后重试；仍失败请检查系统 Keystore 状态。"
        ),
        "NETWORK_ERROR" to ErrorInfo(
            "网络错误",
            "请求未能到达服务器（DNS/超时/连接被拒）。",
            "检查手机网络、地址与端口，必要时用「连接诊断」。"
        ),
        "FILE_LIST_ERROR" to ErrorInfo(
            "文件列表读取失败",
            "电脑端读取目录失败。",
            "确认路径存在且 agent 有读取权限。"
        ),
        // 隧道类：复用 TunnelDiagnosticsHelper 口径
        "HTTP_521" to ErrorInfo(
            "Cloudflare 521：源站服务未启动",
            "Cloudflare Tunnel 隧道已连接，但无法连通 VPS 本地的 OpenCode 服务。",
            "登录 VPS 检查 OpenCode 进程；确认 cloudflared 配置 service 端口与 OpenCode 端口一致。"
        ),
        "HTTP_522" to ErrorInfo(
            "Cloudflare 522：连接源站超时",
            "Cloudflare 与源站的 TCP 握手超时。",
            "检查 VPS 的 cloudflared 守护进程状态与安全组/带宽。"
        ),
        "HTTP_520" to ErrorInfo(
            "Cloudflare 520：源站异常重置",
            "源站向 Cloudflare 返回了空响应或非标准响应。",
            "检查 VPS 上反向代理（Nginx/Caddy）日志与反代协议配置。"
        ),
        "HTTP_524" to ErrorInfo(
            "Cloudflare 524：响应超时",
            "OpenCode 处理耗时超过 Cloudflare 100 秒网关限制。",
            "长任务开启流式输出，保持数据帧活跃以避免静默超时。"
        ),
        "CF_ZERO_TRUST" to ErrorInfo(
            "Cloudflare Zero Trust 拦截",
            "该域名启用了 Cloudflare Access，手机端请求被拦截。",
            "在 Cloudflare One 添加放行规则，或为 API 路径配置 Service Token。"
        ),
        "SAKURAFRP_ERROR" to ErrorInfo(
            "SakuraFrp 穿透异常",
            "穿透服务无法正常转发流量到本地服务器。",
            "检查 SakuraFrp 客户端/隧道是否在线、目标端口是否匹配；大陆节点 80/443 需域名备案。"
        )
    )

    /** 未知错误码回退为通用条目，不抛异常。 */
    fun lookup(code: String): ErrorInfo =
        TABLE[code] ?: ErrorInfo(
            title = "出错了（$code）",
            reason = "发生了未分类的错误。",
            suggestion = "稍后重试；持续出现请导出日志反馈。"
        )
}
