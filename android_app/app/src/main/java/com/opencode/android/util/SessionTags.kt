package com.opencode.android.util

import com.opencode.android.R

/**
 * v4.3 M-4: 会话标签稳定 key。
 * 内部统一用 key（all/default/debug/auto/script）做标识与过滤，显示时才映射到
 * 对应语言的文案。用户自建标签（非 key）原样显示。
 */
const val TAG_ALL = "all"
const val TAG_DEFAULT = "default"
const val TAG_DEBUG = "debug"
const val TAG_AUTO = "auto"
const val TAG_SCRIPT = "script"

/** 内置标签 key → 文案资源。 */
val TAG_KEY_TO_RES: Map<String, Int> = mapOf(
    TAG_ALL to R.string.tag_all,
    TAG_DEFAULT to R.string.tag_default,
    TAG_DEBUG to R.string.tag_debug,
    TAG_AUTO to R.string.tag_auto,
    TAG_SCRIPT to R.string.tag_script
)

/** 存量中文标签 → key（一次性迁移）。 */
private val LEGACY_TAG_TO_KEY: Map<String, String> = mapOf(
    "全部" to TAG_ALL,
    "默认" to TAG_DEFAULT,
    "代码调试" to TAG_DEBUG,
    "自动化任务" to TAG_AUTO,
    "脚本生成" to TAG_SCRIPT
)

/** 内置标签 key 集合（用于区分用户自建标签）。 */
val BUILTIN_TAG_KEYS: Set<String> = TAG_KEY_TO_RES.keys

/**
 * 存量数据迁移：旧中文标签转 key；已经是 key 或用户自建标签则原样返回。
 * 空/null → default。
 */
fun migrateTag(tag: String?): String {
    if (tag.isNullOrEmpty()) return TAG_DEFAULT
    return LEGACY_TAG_TO_KEY[tag] ?: tag
}
