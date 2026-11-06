package com.example.byokchat.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Xử lý ảnh đính kèm: thu nhỏ, xoay đúng chiều, lưu JPEG vào bộ nhớ riêng của app. */
object ImageStore {
    private const val MAX_SIDE = 1568

    fun import(ctx: Context, uri: Uri): String? {
        return try {
            val resolver = ctx.contentResolver
            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE * 2) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return null

            val m = Matrix()
            val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            if (scale < 1f) m.postScale(scale, scale)
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            val bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)

            val dir = File(ctx.filesDir, "images").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

fun imageBase64(path: String): String? =
    runCatching { Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP) }.getOrNull()
