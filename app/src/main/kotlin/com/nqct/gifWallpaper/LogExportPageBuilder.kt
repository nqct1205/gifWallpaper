package com.nqct.gifWallpaper

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// 日志导出页。
//
// 展示三类日志各自的条目数与导出按钮，另有"全部导出"和"清空"。
// 导出由 Activity 处理（文件选择器），本页只负责触发回调。
//
// 【后续扩展】如需新增类别（如"传感器日志"），在 LogBuffer 加缓冲，
// 本页加一行按钮即可，无需改动架构。
class LogExportPageBuilder(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onBack()
        fun onExportGeneral()
        fun onExportPerf()
        fun onExportError()
        fun onExportAll()
        fun onClearLogs()
    }

    fun build(): View {
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
            setOnClickListener { callbacks.onBack() }
        })
        topBar.addView(TextView(activity).apply {
            text = "日志导出"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(20, 0, 0, 0)
        })
        root.addView(topBar)

        root.addView(TextView(activity).apply {
            text = "分类导出便于排查问题。\n" +
                "普通日志：状态切换、传感器、配置加载、用户操作\n" +
                "性能日志：帧耗时超限、内存超限、卡顿、切换耗时\n" +
                "错误日志：异常、加载/导入/导出失败"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 15, 0, 15)
        })

        // 三类
        root.addView(buildCategoryRow(
            title = "普通日志",
            count = LogBuffer.sizeGeneral(),
            onExport = { callbacks.onExportGeneral() }
        ), matchWrap(bottom = 10))

        root.addView(buildCategoryRow(
            title = "性能日志",
            count = LogBuffer.sizePerf(),
            onExport = { callbacks.onExportPerf() }
        ), matchWrap(bottom = 10))

        root.addView(buildCategoryRow(
            title = "错误日志",
            count = LogBuffer.sizeError(),
            onExport = { callbacks.onExportError() }
        ), matchWrap(bottom = 20))

        // 全部导出
        root.addView(Button(activity).apply {
            text = "导出全部"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener { callbacks.onExportAll() }
        }, matchWrap(bottom = 10))

        // 清空
        root.addView(Button(activity).apply {
            text = "清空全部日志"
            setTextColor(Color.RED)
            setOnClickListener { confirmClear() }
        }, matchWrap(bottom = 10))

        root.addView(TextView(activity).apply {
            text = "日志缓冲有容量上限（普通 2000，性能 500，错误 500），超出后丢弃最旧的。"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 0)
        })

        return root
    }

    private fun buildCategoryRow(
        title: String,
        count: Int,
        onExport: () -> Unit
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.WHITE)
        }
        row.addView(TextView(activity).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(activity).apply {
            text = "$count 条"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 20, 0)
        })
        row.addView(Button(activity).apply {
            text = "导出"
            textSize = 12f
            isEnabled = count > 0
            setOnClickListener { onExport() }
        })
        return row
    }

    private fun confirmClear() {
        AlertDialog.Builder(activity)
            .setTitle("清空日志")
            .setMessage("确定清空所有类别的日志？此操作不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                LogBuffer.clearAll()
                callbacks.onClearLogs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, bottom) }
}