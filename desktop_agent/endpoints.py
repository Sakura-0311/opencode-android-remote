"""
B1: opencode 上游端点配置表 + GET /doc 契约探测。

背景：agent 曾把端点路径硬编码在各处字符串字面量里，opencode 上游一改路径
（/event -> /global/event 的教训，见 N-3）就得全仓库 grep。现在所有上游端点
集中在 ENDPOINTS，启动时用 GET /doc 拉取 OpenAPI 规范做一次契约校验：
关键端点缺失 -> 明确拒绝启动并列出缺失项（见 verify_contract）；
/doc 不存在（老版本）-> 降级为警告，不硬拦。
"""
import logging
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

logger = logging.getLogger("OpenCodeContract")


@dataclass(frozen=True)
class Endpoint:
    method: str
    path: str                       # OpenAPI 风格模板，如 /session/{id}/abort
    required: bool = True
    candidates: Tuple[str, ...] = ()  # 多候选路径（版本差异），至少其一存在即可


# B1: 上游端点唯一事实来源。新增上游调用只加这里，不在业务代码里写字面量。
ENDPOINTS: Dict[str, Endpoint] = {
    "health":             Endpoint("GET",  "/global/health"),
    "session_list":       Endpoint("GET",  "/session"),
    "session_create":     Endpoint("POST", "/session"),
    "session_abort":      Endpoint("POST", "/session/{id}/abort"),
    "permission_respond": Endpoint("POST", "/session/{id}/permissions/{permissionID}"),
    "prompt_async":       Endpoint("POST", "/session/{id}/prompt_async"),
    "agent_list":         Endpoint("GET",  "/agent"),
    "providers":          Endpoint("GET",  "/config/providers"),
    "project_list":       Endpoint("GET",  "/project"),
    "project_current":    Endpoint("GET",  "/project/current"),
    "vcs":                Endpoint("GET",  "/vcs"),
    # N-3: SSE 在不同 opencode 版本下是 /event 或 /global/event，二者其一存在即过
    "event_stream":       Endpoint("GET",  "/event", candidates=("/event", "/global/event")),
}
# /doc 本身是探测工具，不列入被校验集合。


def build_url(name: str, base_url: str, **params) -> str:
    """按端点名拼出完整 URL，path 模板用 **params 填充。"""
    ep = ENDPOINTS[name]
    return f"{base_url.rstrip('/')}{ep.path.format(**params)}"


@dataclass
class ContractResult:
    ok: bool
    missing: List[str] = field(default_factory=list)  # 缺失的 "METHOD path"
    opencode_version: Optional[str] = None
    doc_available: bool = True


async def verify_contract(session, base_url: str, password: Optional[str] = None) -> ContractResult:
    """
    B1: GET /doc 拉取 OpenAPI 规范，校验 ENDPOINTS 里 required 的关键端点是否存在。

    返回 ContractResult：
      - /doc 404/连不上 -> doc_available=False（老版本兼容，不硬拦，由调用方降级警告）
      - 规范里缺关键端点 -> ok=False，missing 列出缺失项（调用方拒绝启动）
    """
    clean = base_url.rstrip("/")
    url = f"{clean}/doc"
    # B1: 延迟导入，避免 endpoints <-> opencode_api 循环依赖
    from opencode_api import get_auth_headers
    try:
        import aiohttp
        async with session.get(
            url, headers=get_auth_headers(password),
            timeout=aiohttp.ClientTimeout(total=10.0),
        ) as resp:
            if resp.status == 404:
                logger.warning("B1: opencode 未提供 /doc（老版本），跳过端点契约校验")
                return ContractResult(ok=True, doc_available=False)
            if resp.status != 200:
                logger.warning(f"B1: GET /doc -> HTTP {resp.status}，跳过端点契约校验")
                return ContractResult(ok=True, doc_available=False)
            spec = await resp.json()
    except Exception as e:
        logger.warning(f"B1: 获取 /doc 失败（{e}），跳过端点契约校验")
        return ContractResult(ok=True, doc_available=False)

    paths = set((spec.get("paths") or {}).keys())
    missing: List[str] = []
    for name, ep in ENDPOINTS.items():
        if not ep.required:
            continue
        check_paths = ep.candidates if ep.candidates else (ep.path,)
        if not any(p in paths for p in check_paths):
            missing.append(f"{ep.method} {ep.path}")

    version = (spec.get("info") or {}).get("version")
    if missing:
        logger.error(f"B1: opencode 契约不兼容，缺失关键端点: {missing}")
    return ContractResult(ok=not missing, missing=missing,
                          opencode_version=version, doc_available=True)
