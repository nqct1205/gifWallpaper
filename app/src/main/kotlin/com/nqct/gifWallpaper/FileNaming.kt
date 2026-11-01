package com.nqct.gifWallpaper

/**
 * 统一清洗用户输入的文件名主体（不含扩展名）。
 * - 去除 Windows/Android 非法字符
 * - 去除首尾的 . 和空格（避免隐藏文件 / ".."）
 * - 空名兜底
 * - 超长截断（保留 200 字符安全边界）
 */
internal fun sanitizeFileName(name: String): String {
    var n = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    n = n.trim('.', ' ')
    if (n.isEmpty()) n = "anim_${System.currentTimeMillis()}"
    if (n.length > 200) n = n.substring(0, 200)
    return n
}