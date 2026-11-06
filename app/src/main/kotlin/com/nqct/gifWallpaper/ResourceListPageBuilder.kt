package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 资源列表页。
 *
 * 【职责】展示磁盘上所有 GIF 资源的元信息；提供导入与删除入口。
 * 删除时由 [MainActivity] 处理"提示引用方 + 删磁盘文件 + 同步清理"。
 *
 * 【元信息】尺寸 / 帧数 / 时长全部来自文件本身，用户不可改。
 */
class ResourceListPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onImportGif()
        fun onDeleteResource(resourceId: String)
    }

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- 顶栏 ----
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "资源管理"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 导入按钮 ----
        root.addView(Button(activity).apply {
            text = "导入新 GIF"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onImportGif() }
        }, matchWrap(top = 20, bottom = 20))

        // ---- 列表 ----
        if (config.resources.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "暂无资源，请先导入 GIF。"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            // 用 toSortedMap 保证展示顺序稳定，与磁盘无关
            for ((id, res) in config.resources.toSortedMap()) {
                root.addView(buildResourceItem(id, res), matchWrap(bottom = 10))
            }
        }

        return root
    }

    private fun buildResourceItem(id: String, res: ResourceItem): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            text = id
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(Button(activity).apply {
            text = "删除"
            setTextColor(Color.RED)
            setOnClickListener { callbacks.onDeleteResource(id) }
        })
        card.addView(headerRow)

        val info = res.info
        card.addView(TextView(activity).apply {
            text = if (info != null) {
                "尺寸: ${info.width}x${info.height}  |  帧数: ${info.frameCount}  |  时长: ${info.totalDelayMs / 1000f} 秒"
            } else {
                "文件: ${res.fileName}  (解析失败)"
            }
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })
        return card
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}