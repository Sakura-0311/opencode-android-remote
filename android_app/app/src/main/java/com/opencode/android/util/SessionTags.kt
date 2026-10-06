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

/**
 * 规范化标签：空/null → default；key 或用户自建标签原样返回。
 *
 * v5.0.2: 删除「存量中文标签 → key」的迁移映射表。项目尚无线上用户，
 * 不存在带旧中文标签的存量数据；保留那张表只会让代码里无谓地留一串
 * 硬编码中文（且看起来像 i18n 遗漏）。
 */
fun migrateTag(tag: String?): String {
    if (tag.isNullOrEmpty()) return TAG_DEFAULT
    return tag
}
