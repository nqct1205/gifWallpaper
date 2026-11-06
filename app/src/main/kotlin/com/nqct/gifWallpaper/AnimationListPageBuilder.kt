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
 * 动画列表页。
 *
 * 【展示】标题显示“动画名 (id)”；信息行显示资源引用数、被状态引用数。
 * 点击行进入编辑页；行内提供删除按钮。
 *
 * 【删除约束】被状态引用的动画**不允许删除**（提示先改状态）。
 * 由 [MainActivity.deleteAnimation] 实现，本页只负责触发。
 */
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
            text = "动画管理"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        // ---- 新建按钮 ----
        root.addView(Button(activity).apply {
            text = "新建动画"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onCreateAnimation() }
        }, matchWrap(top = 20, bottom = 20))

        // ---- 列表 ----
        if (config.animations.isEmpty()) {
            root.addView(TextView(activity).apply {
                text = "暂无动画，请先新建。"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                setPadding(0, 50, 0, 50)
            })
        } else {
            for ((id, anim) in config.animations.toSortedMap()) {
                root.addView(buildAnimationItem(id, anim), matchWrap(bottom = 10))
            }
        }

        return root
    }

    private fun buildAnimationItem(id: String, anim: AnimationItem): View {
        val usedByStates = config.states.values.count { it.animationId == id }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
            setOnClickListener { callbacks.onEditAnimation(id) }
        }

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(activity).apply {
            // 标题：名字 (id)，名字为空或等于 id 时仅显示 id
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
        card.addView(headerRow)

        card.addView(TextView(activity).apply {
            // 标题已含 id，信息行只放资源引用数 / 被引用数
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

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top, 0, bottom) }
}