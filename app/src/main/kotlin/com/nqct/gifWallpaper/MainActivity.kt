package com.nqct.gifWallpaper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var tvGif1: TextView
    private lateinit var tvGif2: TextView
    private lateinit var etMinTime: EditText
    private lateinit var etMaxTime: EditText

    // 修复：不能在这里直接 File(filesDir, ...)，必须用 lateinit 延迟初始化
    private lateinit var fileGif1: File
    private lateinit var fileGif2: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 修复：在 onCreate 里初始化，此时上下文已就绪，不会闪退
        fileGif1 = File(filesDir, "gif1.gif")
        fileGif2 = File(filesDir, "gif2.gif")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50) 
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- 1. 选择 GIF 1 ---
        val btnSelect1 = Button(this).apply {
            text = "选择主 GIF (待机)"
            setOnClickListener { selectGif(1) }
        }
        tvGif1 = TextView(this).apply {
            text = if (fileGif1.exists()) "已设置: ${fileGif1.length() / 1024} KB" else "未设置"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        layout.addView(btnSelect1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(tvGif1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- 2. 选择 GIF 2 ---
        val btnSelect2 = Button(this).apply {
            text = "选择随机触发的 GIF (副)"
            setOnClickListener { selectGif(2) }
        }
        tvGif2 = TextView(this).apply {
            text = if (fileGif2.exists()) "已设置: ${fileGif2.length() / 1024} KB" else "未设置"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }
        layout.addView(btnSelect2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(tvGif2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- 3. 随机时间设置 ---
        val tvTimeTitle = TextView(this).apply { text = "随机触发间隔设置 (单位: 秒)"; textSize = 18f; setPadding(0, 0, 0, 20) }
        layout.addView(tvTimeTitle)

        val timeLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        etMinTime = EditText(this).apply { hint = "最小间隔(如10)"; setText("15") }
        etMaxTime = EditText(this).apply { hint = "最大间隔(如30)"; setText("45") }
        timeLayout.addView(etMinTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeLayout.addView(etMaxTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(timeLayout)

        val btnSave = Button(this).apply {
            text = "保存参数"
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "参数已保存！", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnSave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- 4. 去设置壁纸 ---
        val btnSetWallpaper = Button(this).apply {
            text = "去应用动态壁纸"
            setOnClickListener {
                saveSettings()
                val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@MainActivity, GifWallpaperService::class.java)
                    )
                }
                startActivity(intent)
            }
        }
        layout.addView(btnSetWallpaper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(layout)
        loadSettings()
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
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (requestCode == 1) {
                    tvGif1.text = "已设置: ${targetFile.length() / 1024} KB"
                } else {
                    tvGif2.text = "已设置: ${targetFile.length() / 1024} KB"
                }
            } catch (e: Exception) {
                Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("minTime", etMinTime.text.toString().toIntOrNull() ?: 15)
            .putInt("maxTime", etMaxTime.text.toString().toIntOrNull() ?: 45)
            .apply()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("PetPrefs", Context.MODE_PRIVATE)
        etMinTime.setText(prefs.getInt("minTime", 15).toString())
        etMaxTime.setText(prefs.getInt("maxTime", 45).toString())
    }
}