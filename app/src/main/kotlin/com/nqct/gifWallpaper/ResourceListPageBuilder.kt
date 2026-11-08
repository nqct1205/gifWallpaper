package com.nqct.gifWallpaper

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// 资源列表页。骨架由 ListPageScaffold 提供。
// 每个卡片显示资源信息与当前播放速度；点击卡片进入编辑页。
class ResourceListPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onImportGif()
        fun onDeleteResource(resourceId: String)
        fun onEditResource(resourceId: String)
        fun onReorderResources(fromIndex: Int, toIndex: Int)
    }

    fun build(scrollView: ScrollView): View {
        return ListPageScaffold.build(
            activity = activity,
            scrollView = scrollView,
            title = "资源管理",
            actionText = "导入新 GIF",
            actionColor = Color.parseColor("#4CAF50"),
            emptyText = "暂无资源，请先导入 GIF。",
            onBack = { callbacks.onBack() },
            onAction = { callbacks.onImportGif() },
            itemCount = config.resources.size,
            bindItems = { column ->
                val entries = config.resources.entries.toList()
                column.setItems(
                    items = entries,
                    scrollView = scrollView,
                    itemBuilder = { entry, _, handle ->
                        buildResourceItem(entry.key, entry.value, handle)
                    },
                    onReorder = { from, to -> callbacks.onReorderResources(from, to) }
                )
            }
        )
    }

    private fun buildResourceItem(id: String, res: ResourceItem, handle: View): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            setOnClickListener { callbacks.onEditResource(id) }
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
        headerRow.addView(handle)
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

        // 当前速度
        card.addView(TextView(activity).apply {
            text = "速度: ${res.speedDescription()}"
            textSize = 12f
            setTextColor(Color.parseColor("#2196F3"))
            setPadding(0, 3, 0, 0)
        })

        return card
    }
}