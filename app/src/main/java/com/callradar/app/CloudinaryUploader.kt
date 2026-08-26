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

    // ── [중지 2026-08-27] 사진 외부 업로드를 하지 않는다 ────────────────
    //
    //  왜 껐나:
    //   · 영수증·전표 사진이 Cloudinary(미국)로 나가고 있었는데
    //     개인정보처리방침 제5조 수탁자 표엔 Render·카카오뿐이었고
    //     제4조엔 "제3자에게 제공하지 않습니다"라고 적혀 있었다. 문서와 실제가 달랐다.
    //   · 올라간 사진을 지우는 코드가 어디에도 없었다(방침의 '탈퇴 후 30일 내 파기'와 불일치).
    //   · unsigned 업로드라 URL만 알면 로그인 없이 누구나 열렸다.
    //
    //  이중 잠금: 여기서 항상 null 을 돌려주고, Cloudinary 콘솔의 preset 'callradar' 도
    //  Signed 로 바꿔 잠갔다(실측 확인: HTTP 400 "must be whitelisted for unsigned uploads").
    //  구버전 앱이 계속 시도해도 서버 쪽에서 막힌다.
    //
    //  아래 실제 업로드 코드는 지우지 않고 남겨 둔다 — 나중에 국내 업체나 자사 서버로
    //  옮길 때 압축 로직을 그대로 재사용하기 위해서다. 호출부는 이 함수 하나뿐이다.
    fun upload(context: Context, imageUri: Uri): String? = null

    @Suppress("unused")
    private fun uploadDisabled(context: Context, imageUri: Uri): String? {
        return try {
            val compressed = compressImage(context, imageUri) ?: return null
            uploadBytes(compressed)
        } catch (e: Exception) { null }
    }

    // 이미지 압축: 다운샘플링 + 리사이즈 + JPEG 품질 조정으로 500KB 이하
    // [구글 권장] inJustDecodeBounds로 크기만 먼저 읽고 inSampleSize로 다운샘플링 디코드 —
    //  고해상도 사진(예: 108MP 1.2억 화소)을 풀사이즈로 메모리에 올리던 문제 해결(OOM 방지·로딩 단축).
    private fun compressImage(context: Context, uri: Uri): ByteArray? {
        // 1) 크기만 읽기 (픽셀 디코드 없음)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) } ?: return null
        val rawW = boundsOpts.outWidth; val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null
        // 2) MAX_DIMENSION의 2배 이하가 될 때까지 절반씩 다운샘플(2의 거듭제곱)
        var sample = 1
        while (maxOf(rawW, rawH) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) } ?: return null

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
        // [보안] Cloudinary는 제3자 → 우리 세션 토큰(Authorization)을 붙이지 않는다(토큰 유출 방지).
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
