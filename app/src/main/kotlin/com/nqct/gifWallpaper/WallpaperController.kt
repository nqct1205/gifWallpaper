package com.nqct.gifWallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

// 壁纸操作。
//
// 【职责边界】只负责调起系统接口，不弹窗、不 Toast、不刷新页面。
object WallpaperController {

    // 打开系统"设置动态壁纸"界面。返回 false 表示系统不支持。
    fun applyLiveWallpaper(context: Context): Boolean {
        return try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, GifWallpaperService::class.java)
                )
            }
            context.startActivity(intent)
            LogBuffer.i("应用动态壁纸")
            true
        } catch (e: Exception) {
            LogBuffer.e("应用动态壁纸失败", e)
            false
        }
    }

    // 清除动态壁纸，恢复系统默认。返回 null 表示成功，否则为错误信息。
    fun restoreSystemWallpaper(context: Context): String? {
        return try {
            WallpaperManager.getInstance(context).clear()
            LogBuffer.i("恢复系统默认壁纸")
            null
        } catch (e: Exception) {
            LogBuffer.e("恢复系统默认壁纸失败", e)
            e.message ?: "未知错误"
        }
    }
}