package com.HanFeng.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.doOnAttach
import com.HanFeng.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

private val supportedImageExtensions = listOf("png", "jpg", "jpeg", "webp")
private val customVisualsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private const val CUSTOM_VISUALS_JOB_TAG_KEY = -1008611
private const val CUSTOM_VISUALS_CONTEXT_TAG_KEY = -1008612
private const val CUSTOM_VISUALS_BG_ID_KEY = -1008613

/**
 * asset 自定义背景缺失时的哨兵Drawable：命中即表示"确认无图"，
 * 跳过重复 IO，调用方需与真实背景区分（不能 set 到 ImageView）
 */
private val EMPTY_BACKGROUND_MARKER: Drawable = android.graphics.drawable.ColorDrawable(0x00000000)

/** 为 Activity 应用自定义背景图：优先复用布局中已存在的 ivBackground，否则在内容根部动态添加。 */
fun androidx.appcompat.app.AppCompatActivity.ensureAppBackground() {
    val customPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(this)
    val hasCustom = !customPath.isNullOrEmpty()
    val ivBg = findViewById<ImageView>(R.id.ivBackground)
        ?: findViewById<ImageView>(CUSTOM_VISUALS_BG_ID_KEY)
        ?: run {
            val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
            ImageView(this).apply {
                id = CUSTOM_VISUALS_BG_ID_KEY
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                content.addView(this, 0)
            }
        }
    // 子页面根视图常带不透明的 @drawable/bg_main（内置渐变+tint），会盖住动态添加的背景层。
    // 有自定义背景时移除根背景让自定义图透出；无自定义背景时保留内置背景不变。
    if (hasCustom) {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        for (i in 0 until (content?.childCount ?: 0)) {
            val child = content?.getChildAt(i) ?: continue
            if (child !== ivBg && child.background != null) {
                child.background = null
            }
        }
    }
    if (hasCustom) {
        ivBg.applyCustomFileBackground(customPath)
    } else {
        ivBg.applyCustomAssetBackground("custom/background")
    }
    // 为液态玻璃效果添加背景高斯模糊 (Android 12+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes.blurBehindRadius = 24
    }
}

/**
 * Bitmap 参考：记录"被 cache 持有但还可能被 ImageView 引用"的 Bitmap 集合，
 * 提供 closeDrawableFor(view) 主动分离路径，让 ImageView release 时主动 dirty cache 条目，
 * 在 eviction 时检测这个集合避免 recycle 仍在显示中的 Bitmap。
 */
private val liveDisplayedBitmaps = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<android.graphics.Bitmap, Boolean>())

// 内存缓存:解码后的 Drawable 复用,避免每次 onResume 都重新 IO+解码
// maxSize 6MB 即够覆盖 3-6 张普通背景。sizeOf 用 byte 估值，并限制单条以避免一张超高分辨率图把大小炸成 60MB，
// 导致整个 cache 仅剩 1 条记录就被驱逐。
// entryRemoved 仅在 eviction 且 Bitmap 不在 liveDisplayedBitmaps 中时 recycle，
// 避免 recycle 后被仍引用的 ImageView 绘制抛 "Canvas: trying to use a recycled bitmap"。
private val customBackgroundDrawableCache = object : LruCache<String, Drawable>(6 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Drawable): Int {
        return runCatching {
            val w = value.intrinsicWidth.coerceAtLeast(1)
            val h = value.intrinsicHeight.coerceAtLeast(1)
            val byteSize = w.toLong() * h * 4L
            // 单条上限 2MB；下限 64KB（保证空 Drawable 也算占用）
            byteSize.coerceIn(64L * 1024L, 2L * 1024L * 1024L).toInt()
        }.getOrDefault(256 * 1024)
    }

    override fun entryRemoved(evicted: Boolean, key: String, oldValue: Drawable, newValue: Drawable?) {
        // 仅回收因 eviction 而被丢弃的旧值；newValue 非空表示是 put 覆盖，需更小心判断
        if (newValue != null && newValue === oldValue) return
        runCatching {
            val bmp = (oldValue as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@runCatching
            if (!bmp.isRecycled && !bmp.isMutable && bmp !in liveDisplayedBitmaps) {
                bmp.recycle()
            }
        }
    }
}

fun ImageView.applyCustomAssetBackground(assetBaseName: String) {
    cancelCustomVisualsJob(this)
    if (!isAttachedToWindow) {
        // onCreate 等时机调用时 view 尚未 attach，attach 后再加载
        doOnAttach { applyCustomAssetBackground(assetBaseName) }
        return
    }
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    // 同步命中缓存直接设置,跳过协程开销；命中空标记表示确认无自定义图，直接结束
    customBackgroundDrawableCache.get("asset:$assetBaseName")?.let { cached ->
        if (cached === EMPTY_BACKGROUND_MARKER) return
        markBitmapLive(cached)
        setImageDrawable(cached)
        return
    }
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomAssetDrawable(appContext, assetBaseName)
        }
        if (customDrawable == null) {
            // 无自定义图：缓存空标记，避免每次 onResume 重复打开 4 个扩展名的 asset 流
            customBackgroundDrawableCache.put("asset:$assetBaseName", EMPTY_BACKGROUND_MARKER)
            return@launch
        }
        customBackgroundDrawableCache.put("asset:$assetBaseName", customDrawable)
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext && isAttachedToWindow) {
            markBitmapLive(customDrawable)
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

fun ImageView.applyCustomFileBackground(filePath: String?) {
    cancelCustomVisualsJob(this)
    if (filePath == null) return
    if (!isAttachedToWindow) {
        doOnAttach { applyCustomFileBackground(filePath) }
        return
    }
    val appContext = context.applicationContext
    setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, WeakReference(appContext))
    // 同步命中缓存直接设置,跳过协程开销
    val cacheKey = "file:$filePath:${File(filePath).lastModified()}"
    customBackgroundDrawableCache.get(cacheKey)?.let { cached ->
        markBitmapLive(cached)
        setImageDrawable(cached)
        return
    }
    val job = customVisualsScope.launch {
        val customDrawable = withContext(Dispatchers.IO) {
            loadCustomFileDrawable(appContext, filePath)
        } ?: return@launch
        customBackgroundDrawableCache.put(cacheKey, customDrawable)
        val ctxRef = getTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY) as? WeakReference<*>
        if (ctxRef?.get() == appContext && isAttachedToWindow) {
            markBitmapLive(customDrawable)
            setImageDrawable(customDrawable)
        }
    }
    setTag(CUSTOM_VISUALS_JOB_TAG_KEY, job)
}

/**
 * 把 cache 里的 Bitmap 标记为"有 ImageView 显示中"，避免 LruCache eviction 时 recycle 导致绘制崩溃。
 * Drawable 的 Bitmap 引用从 setImageDrawable 时算开始，到 cancelCustomVisualsJob 时算结束。
 */
private fun markBitmapLive(drawable: Drawable?) {
    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
        liveDisplayedBitmaps.add(it)
    }
}

private fun unmarkBitmapLive(drawable: Drawable?) {
    (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
        liveDisplayedBitmaps.remove(it)
    }
}

private fun cancelCustomVisualsJob(view: ImageView) {
    (view.getTag(CUSTOM_VISUALS_JOB_TAG_KEY) as? Job)?.cancel()
    view.setTag(CUSTOM_VISUALS_JOB_TAG_KEY, null)
    view.setTag(CUSTOM_VISUALS_CONTEXT_TAG_KEY, null)
    // ImageView 当前显示的 Bitmap 不再被本 view 引用，从 live 集合移除让 LRU 可以回收
    unmarkBitmapLive(view.drawable)
}

private fun decodeSampledDrawable(input: java.io.InputStream, maxDimension: Int = 1920): Drawable? {
    return runCatching {
        // 用字节数组备份，因为 inJustDecodeBounds=true 第一次 decodeStream 会消费输入流，
        // 第二次实际解码需要重新构造输入流，否则读到空数据返回 null
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return@runCatching null
        val boundsOpts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val (origW, origH) = boundsOpts.outWidth to boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return@runCatching null
        var sample = 1
        while (origW / sample > maxDimension || origH / sample > maxDimension) {
            sample *= 2
        }
        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        bmp?.let { android.graphics.drawable.BitmapDrawable(it) }
    }.getOrNull()
}

fun loadCustomAssetDrawable(context: Context, assetBaseName: String): Drawable? {
    for (extension in supportedImageExtensions) {
        val assetPath = "$assetBaseName.$extension"
        val drawable = runCatching {
            context.assets.open(assetPath).use { input ->
                decodeSampledDrawable(input)
            }
        }.getOrNull()
        if (drawable != null) {
            return drawable
        }
    }
    return null
}

fun loadCustomFileDrawable(context: Context, filePath: String): Drawable? {
    val file = File(filePath)
    if (!file.exists()) return null
    return runCatching {
        file.inputStream().use { input ->
            decodeSampledDrawable(input)
        }
    }.getOrNull()
}

/**
 * 移除某路径对应的内存缓存条目，供背景图被删除时调用，避免 SP 已更新但缓存仍命中旧图。
 * LruCache.remove 不会触发 entryRemoved，不会 recycle 正在显示的 Bitmap，可安全调用。
 */
fun clearCustomFileBackgroundCache(path: String?) {
    if (path.isNullOrBlank()) return
    val prefix = "file:$path:"
    customBackgroundDrawableCache.snapshot().keys
        .filter { it.startsWith(prefix) }
        .forEach { customBackgroundDrawableCache.remove(it) }
}
