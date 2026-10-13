package com.lrh.healthvivo.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

object MealImage {
    suspend fun preview(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val factor = max(info.size.width, info.size.height).toFloat() / 512
            if (factor > 1) decoder.setTargetSize((info.size.width / factor).toInt().coerceAtLeast(1), (info.size.height / factor).toInt().coerceAtLeast(1))
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply { inSampleSize = max(bounds.outWidth, bounds.outHeight).div(512).coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("无法读取图片")
        }
    }
    suspend fun dataUrl(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                require(info.size.width.toLong() * info.size.height <= 150_000_000) { "图片尺寸过大" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val factor = max(info.size.width, info.size.height).toFloat() / 1600
                if (factor > 1) decoder.setTargetSize((info.size.width / factor).toInt().coerceAtLeast(1), (info.size.height / factor).toInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply { inSampleSize = max(bounds.outWidth, bounds.outHeight).div(1600).coerceAtLeast(1) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("无法读取图片")
        }
        try {
            val bytes = ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)); output.toByteArray() }
            require(bytes.size <= 3 * 1024 * 1024) { "图片压缩后仍然过大，请换一张图片" }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
}
