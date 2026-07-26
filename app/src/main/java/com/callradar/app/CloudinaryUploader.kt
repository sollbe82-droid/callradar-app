package com.callradar.app

// ===== CloudinaryUploader v1 (2026-07-13) =====
// 사진을 300~500KB로 압축 후 Cloudinary에 Unsigned 업로드
// Cloud Name: iwfkusoe, Upload Preset: callradar (Unsigned)
// Secret 불필요 — Unsigned preset이라 앱에서 직접 업로드

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object CloudinaryUploader {
    private const val CLOUD_NAME = "iwfkusoe"
    private const val UPLOAD_PRESET = "callradar"
    private const val TARGET_MAX_BYTES = 500 * 1024   // 500KB 목표
    private const val MAX_DIMENSION = 1600            // 긴 변 최대 1600px

    // Uri(카메라/갤러리 사진) → 압축 → Cloudinary 업로드 → secure_url 반환 (실패 시 null)
    fun upload(context: Context, imageUri: Uri): String? {
        return try {
            val compressed = compressImage(context, imageUri) ?: return null
            uploadBytes(compressed)
        } catch (e: Exception) { null }
    }

    // 이미지 압축: 리사이즈 + JPEG 품질 조정으로 500KB 이하
    private fun compressImage(context: Context, uri: Uri): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(input)
        input.close()
        if (original == null) return null

        // 긴 변 기준 리사이즈
        val w = original.width; val h = original.height
        val scale = if (maxOf(w, h) > MAX_DIMENSION) MAX_DIMENSION.toFloat() / maxOf(w, h) else 1f
        val resized = if (scale < 1f)
            Bitmap.createScaledBitmap(original, (w * scale).toInt(), (h * scale).toInt(), true)
        else original

        // 품질 낮춰가며 500KB 이하로
        var quality = 85
        var bytes: ByteArray
        do {
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bytes = out.toByteArray()
            quality -= 10
        } while (bytes.size > TARGET_MAX_BYTES && quality > 30)
        return bytes
    }

    // multipart/form-data로 Cloudinary 업로드
    private fun uploadBytes(imageBytes: ByteArray): String? {
        val url = URL("https://api.cloudinary.com/v1_1/$CLOUD_NAME/image/upload")
        val boundary = "----CallRadar${System.currentTimeMillis()}"
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            // upload_preset 필드
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n")
            out.writeBytes("$UPLOAD_PRESET\r\n")
            // file 필드
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"receipt.jpg\"\r\n")
            out.writeBytes("Content-Type: image/jpeg\r\n\r\n")
            out.write(imageBytes)
            out.writeBytes("\r\n")
            out.writeBytes("--$boundary--\r\n")
            out.flush()
        }
        val code = conn.responseCode
        return if (code in 200..299) {
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optString("secure_url", null)
        } else {
            null
        }
    }
}
