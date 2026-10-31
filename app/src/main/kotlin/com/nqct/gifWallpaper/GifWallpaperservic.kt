package com.nqct.gifWallpaper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvGif1: TextView
    private lateinit var tvGif2: TextView
    private lateinit var tvInfo1: TextView
    private lateinit var tvInfo2: TextView
    private lateinit var etMinTime: EditText
    private lateinit var etMaxTime: EditText

    private lateinit var fileGif1: File
    private lateinit var fileGif2: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileGif1 = File(filesDir, "gif1.gif")
        fileGif2 = File(filesDir, "gif2.gif")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // ===== GIF 1 =====
        val btnSelect1 = Button(this).apply {
            text = "选择主 GIF (待机)"
            setOnClickListener { selectGif(1) }
        }
        tvGif1 = TextView(this).apply {
            text = if (fileGif1.exists()) "已设置: ${fileGif1.length() / 1024} KB" else "未设置"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        tvInfo1 = createInfoView()
        layout.addView(btnSelect1, matchWrap())
        layout.addView(tvGif1, matchWrap())
        layout.addView(tvInfo1, matchWrap())

        // ===== GIF 2 =====
        val btnSelect2 = Button(this).apply {
            text = "选择随机触发的 GIF (副)"
            setOnClickListener { selectGif(2) }
        }
        tvGif2 = TextView(this).apply {
            text = if (fileGif2.exists()) "已设置: ${fileGif2.length() / 1024} KB" else "未设置"
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 8)
        }
        tvInfo2 = createInfoView()
        layout.addView(btnSelect2, matchWrap())
        layout.addView(tvGif2, matchWrap())
        layout.addView(tvInfo2, matchWrap())

        // ===== 随机时间设置 =====
        val tvTimeTitle = TextView(this).apply {
            text = "随机触发间隔设置 (单位: 秒)"
            textSize = 18f
            setPadding(0, 30, 0, 20)
        }
        layout.addView(tvTimeTitle)

        val timeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        etMinTime = EditText(this).apply { hint = "最小间隔(如10)"; setText("15") }
        etMaxTime = EditText(this).apply { hint = "最大间隔(如30)"; setText("45") }
        timeLayout.addView(etMinTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeLayout.addView(etMaxTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(timeLayout)

        val btnSave = Button(this).apply {
            text = "保存参数"
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "参数已保存！(需重新应用壁纸生效)", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnSave, matchWrap())

        // ===== 去设置壁纸 =====
        val btnSetWallpaper = Button(this).apply {
            text = "去应用动态壁纸"
            setOnClickListener {
                saveSettings()
                try {
                    val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(
                            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, GifWallpaperService::class.java)
                        )
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "系统不支持动态壁纸设置: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        layout.addView(btnSetWallpaper, matchWrap())

        setContentView(layout)

        loadSettings()
        refreshGifInfo(1)
        refreshGifInfo(2)
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun createInfoView(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(Color.DKGRAY)
        setPadding(0, 0, 0, 10)
    }

    private fun selectGif(type: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("image/gif")
        }
        startActivityForResult(intent, type)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            val targetFile = if (requestCode == 1) fileGif1 else fileGif2
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (requestCode == 1) {
                    tvGif1.text = "已设置: ${targetFile.length() / 1024} KB"
                } else {
                    tvGif2.text = "已设置: ${targetFile.length() / 1024} KB"
                }
                refreshGifInfo(requestCode)
            } catch (e: Exception) {
                Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshGifInfo(type: Int) {
        val file = if (type == 1) fileGif1 else fileGif2
        val infoView = if (type == 1) tvInfo1 else tvInfo2

        if (!file.exists()) {
            infoView.text = "未设置"
            return
        }
        infoView.text = "解析中..."

        Thread {
            val info = GifMetadataParser.parse(file)
            val text = buildInfoText(file, info)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    infoView.text = text
                }
            }
        }.start()
    }

    private fun buildInfoText(file: File, info: GifInfo?): String {
        if (!file.exists()) return "未设置"
        val sizeText = formatSize(file.length())
        if (info == null) return "文件大小: $sizeText\n(无法解析 GIF 信息)"

        val loopText = when (info.loopCount) {
            0 -> "无限"
            1 -> "1 次"
            else -> "${info.loopCount} 次"
        }
        val fpsText = if (info.averageFps > 0f) {
            String.format(Locale.US, "%.1f fps", info.averageFps)
        } else {
            "未知"
        }
        val durationText = if (info.totalDelayMs > 0) {
            String.format(Locale.US, "%.2f 秒", info.totalDelayMs / 1000f)
        } else {
            "未知"
        }

        return buildString {
            append("文件大小: ").append(sizeText).append('\n')
            append("尺寸: ").append(info.width).append(" × ").append(info.height).append('\n')
            append("帧数: ").append(info.frameCount).append('\n')
            append("总时长: ").append(durationText).append('\n')
            append("平均帧率: ").append(fpsText).append('\n')
            append("循环: ").append(loopText)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    private fun saveSettings() {
        var min = etMinTime.text.toString().toIntOrNull() ?: 15
        var max = etMaxTime.text.toString().toIntOrNull() ?: 45
        if (min <= 0) min = 1
        if (max <= 0) max = 1
        if (min > max) {
            val t = min; min = max; max = t
        }
        etMinTime.setText(min.toString())
        etMaxTime.setText(max.toString())

        getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("minTime", min)
            .putInt("maxTime", max)
            .apply()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
        etMinTime.setText(prefs.getInt("minTime", 15).toString())
        etMaxTime.setText(prefs.getInt("maxTime", 45).toString())
    }
}