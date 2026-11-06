package com.nqct.gifWallpaper

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import java.util.Locale

/**
 * TextWatcher 便捷基类：只覆写需要的方法。
 *
 * 以 `internal` 共享，供各 PageBuilder 使用；不属于公共 API。
 */
internal open class SimpleTextWatcher : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) {}
}

/**
 * 安全解析颜色字符串。支持带不带 `#` 前缀的 `RRGGBB` / `AARRGGBB`，
 * 以及 [Color.parseColor] 支持的其他格式（`#RGB`、颜色名等）。
 *
 * 解析失败或空串返回 null。
 */
internal fun parseColorSafe(raw: String): Int? {
    if (raw.isBlank()) return null
    return try {
        val hex = if (raw.startsWith("#")) raw else "#$raw"
        Color.parseColor(hex)
    } catch (e: Exception) {
        null
    }
}

/**
 * 将颜色 Int 格式化为规范字符串。
 * - alpha 为 FF：输出 `#RRGGBB`
 * - alpha 非 FF：输出 `#AARRGGBB`
 */
internal fun formatArgbColor(color: Int): String {
    val a = (color ushr 24) and 0xFF
    return if (a == 0xFF) {
        String.format(Locale.US, "#%06X", color and 0xFFFFFF)
    } else {
        String.format(Locale.US, "#%08X", color)
    }
}

// ==================== 名字 + ID 显示 ====================

/**
 * 统一的"名字 (id)"显示格式。
 *
 * 规则：
 *  - [name] 为空：只显示 [id]。
 *  - [name] 与 [id] 完全相同：只显示 [id]（避免 `id (id)` 重复）。
 *  - 其他：显示 `名字 (id)`。
 *
 * 用于所有向用户展示 状态 / 动画 的位置，保证"用户改后的名字在前、id 在后括注"。
 */
internal fun formatNameWithId(name: String, id: String): String {
    val n = name.ifBlank { id }
    return if (n == id) id else "$n ($id)"
}

/** 状态的显示文本：名字 (id)。state 为 null 时返回空串。 */
internal fun formatStateDisplay(state: StateNode?): String =
    if (state == null) "" else formatNameWithId(state.name, state.id)

/** 动画的显示文本：名字 (id)。anim 为 null 时返回空串。 */
internal fun formatAnimDisplay(anim: AnimationItem?): String =
    if (anim == null) "" else formatNameWithId(anim.name, anim.id)