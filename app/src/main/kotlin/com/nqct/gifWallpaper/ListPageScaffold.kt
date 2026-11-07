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

// 列表页脚手架：提取资源 / 动画 / 状态三个列表页的共同骨架。
//
// 【共同点】
//   - 根布局（LinearLayout + padding + 背景色）
//   - 顶栏（返回 + 标题）
//   - 操作按钮（导入 / 新建）
//   - 空列表提示
//   - 有数据时：委托 ReorderableColumn 处理拖动排序
//
// 【差异点（由调用方传入）】
//   - 标题文字、按钮文字、按钮颜色
//   - 空列表提示文字
//   - 数据源与 item 渲染（通过 bindItems 回调注入）
//
// 【好处】改通用骨架（顶栏样式、按钮布局、空列表样式）只改这一处；
// 改拖动样式只改 ReorderableColumn.kt；三个 ListPage 保持精简。
object ListPageScaffold {

    fun build(
        activity: Activity,
        scrollView: ScrollView,
        title: String,
        actionText: String,
        actionColor: Int,
        emptyText: String,
        onBack: () -> Unit,
        onAction: () -> Unit,
        itemCount: Int,
        bindItems: (ReorderableColumn) -> Unit
    ): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // 顶栏
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(Button(activity).apply {
            text = "< 返回"
            setOnClickListener { onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // 操作按钮
        root.addView(Button(activity).apply {
            text = actionText
            setBackgroundColor(actionColor)
            setTextColor(Color.WHITE)
            setOnClickListener { onAction() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 20, 0, 20) })

        // 空列表 or 列表
        if (itemCount == 0) {
            root.addView(TextView(activity).apply {
                text = emptyText
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            val column = ReorderableColumn(activity)
            bindItems(column)
            root.addView(column, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        return root
    }
}