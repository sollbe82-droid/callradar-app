package com.callradar.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * [v96] OCR 전처리 — 사진을 '읽기 좋은 상태'로 만들어서 인식률을 올린다.
 *
 * ─ 왜 필요한가 ───────────────────────────────────────────────────
 * 택시기사가 찍는 영수증은 대부분 **감열지**다. 감열지는
 *   · 바탕이 흰색이 아니라 미색이고
 *   · 인쇄가 검정이 아니라 흐린 회색이며
 *   · 시간이 지나면 더 옅어진다
 * 그래서 사람 눈에는 보여도 OCR 이 글자와 바탕을 못 가른다.
 *
 * 여기서 하는 일은 세 가지뿐이다. 전부 폰 안에서 끝난다.
 *   1. 흑백으로 바꾼다        — 색은 글자 판독에 방해만 된다
 *   2. 대비를 세게 준다        — 흐린 회색 인쇄를 검정 쪽으로 밀어붙인다
 *   3. 너무 작으면 키운다      — ML Kit 은 글자 높이가 어느 정도 돼야 읽는다
 *
 * ─ 하지 않는 것 ──────────────────────────────────────────────────
 * 이진화(threshold)는 일부러 안 한다. 임계값을 잘못 잡으면 흐린 글자가
 * 통째로 날아가서 오히려 더 나빠진다. 대비 강화까지가 안전한 선이다.
 * 실패하면 원본을 그대로 돌려준다 — 전처리 때문에 기능이 죽는 일은 없어야 한다.
 */
object OcrPrep {

    /** ML Kit 이 작은 글자를 놓치지 않는 최소 크기. 이보다 작으면 키운다. */
    private const val MIN_LONG_EDGE = 1200

    /** 메모리 방어 상한. 이보다 크면 굳이 더 키우지 않는다. */
    private const val MAX_LONG_EDGE = 2600

    /**
     * 대비 강화 계수. 1.0 이 원본.
     * 1.6 은 감열지 실물에서 획이 살아나면서도 배경 얼룩이 글자로 번지지 않는 선이다.
     * 더 올리면 종이 접힌 그림자까지 검게 변해 오히려 오인식이 는다.
     */
    private const val CONTRAST = 1.6f

    /** 대비를 올리면 전체가 어두워지므로 밝기를 같이 들어 올려 중간톤을 유지한다. */
    private const val BRIGHTNESS = -40f

    fun prepare(src: Bitmap): Bitmap {
        return try {
            val long = maxOf(src.width, src.height)
            if (long <= 0) return src

            // 1) 크기 맞추기 — 작으면 키우고, 지나치게 크면 줄인다.
            val scale = when {
                long < MIN_LONG_EDGE -> (MIN_LONG_EDGE.toFloat() / long).coerceAtMost(3f)
                long > MAX_LONG_EDGE -> MAX_LONG_EDGE.toFloat() / long
                else -> 1f
            }
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            // 2) 흑백 + 3) 대비 — ColorMatrix 두 개를 이어 붙인다.
            //    saturation 0 = 흑백. 그다음 대비 행렬을 곱한다.
            val gray = ColorMatrix().apply { setSaturation(0f) }
            val contrast = ColorMatrix(floatArrayOf(
                CONTRAST, 0f, 0f, 0f, BRIGHTNESS,
                0f, CONTRAST, 0f, 0f, BRIGHTNESS,
                0f, 0f, CONTRAST, 0f, BRIGHTNESS,
                0f, 0f, 0f, 1f, 0f
            ))
            gray.postConcat(contrast)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(gray)
            }
            canvas.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
            out
        } catch (e: Throwable) {
            // OOM 포함 — 전처리는 어디까지나 '더 잘 읽히게' 하려는 것이다.
            // 실패하면 원본으로 그냥 진행한다. 기능이 멈추면 안 된다.
            src
        }
    }
}
