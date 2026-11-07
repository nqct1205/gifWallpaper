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

// 动画列表页。骨架由 ListPageScaffold 提供，本类只负责数据源与 item 渲染。
class AnimationListPageBuilder(
    private val activity: Activity,
    private val config: ProjectConfig,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onCreateAnimation()
        fun onEditAnimation(animationId: String)
        fun onDeleteAnimation(animationId: String)
        fun onReorderAnimations(fromIndex: Int, toIndex: Int)
    }

    fun build(scrollView: ScrollView): View {
        return ListPageScaffold.build(
            activity = activity,
            scrollView = scrollView,
            title = "动画管理",
            actionText = "新建动画",
            actionColor = Color.parseColor("#4CAF50"),
            emptyText = "暂无动画，请先新建。",
            onBack = { callbacks.onBack() },
            onAction = { callbacks.onCreateAnimation() },
            itemCount = config.animations.size,
            bindItems = { column ->
                val entries = config.animations.entries.toList()
                column.setItems(
                    items = entries,
                    scrollView = scrollView,
                    itemBuilder = { entry, _, handle ->
                        buildAnimationItem(entry.key, entry.value, handle)
                    },
                    onReorder = { from, to -> callbacks.onReorderAnimations(from, to) }
                )
            }
        )
    }

    private fun buildAnimationItem(id: String, anim: AnimationItem, handle: View): View {
        val usedByStates = config.states.values.count { it.animationId == id }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            setOnClickListener { callbacks.onEditAnimation(id) }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            text = formatAnimDisplay(anim)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(Button(activity).apply {
            text = "删除"
            setTextColor(Color.RED)
            setOnClickListener { callbacks.onDeleteAnimation(id) }
        })
        headerRow.addView(handle)
        card.addView(headerRow)

        card.addView(TextView(activity).apply {
            text = buildString {
                append("资源引用: ${anim.resources.size}")
                if (anim.backgroundColor.isNotBlank()) append("  |  背景色: ${anim.backgroundColor}")
                if (usedByStates > 0) append("  |  被 $usedByStates 个状态引用")
            }
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        return card
    }
}