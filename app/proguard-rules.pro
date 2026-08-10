# ===== CallRadar R8 rules (v51 난독화) =====
# 콜레이더 자체 코드는 리플렉션 없음(org.json 문자열키 파싱) → 난독화 안전.
# 매니페스트 선언 컴포넌트(Activity/Service/Receiver)는 R8가 자동 keep.
# 위험은 서드파티 SDK뿐 → 아래로 보호.

# --- Kakao 지도(vectormap) + 로그인 SDK : 내부 reflection/native ---
-keep class com.kakao.** { *; }
-keep interface com.kakao.** { *; }
-dontwarn com.kakao.**

# --- Google ML Kit(한글 OCR) + GMS 위치 : 방어적 keep ---
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.**

# --- ZXing(QR) ---
-dontwarn com.google.zxing.**

# --- Kotlin coroutines / metadata ---
-dontwarn kotlinx.**

# --- Enum values()/valueOf (일부 SDK가 리플렉션 사용) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- BuildConfig(KAKAO_NATIVE_KEY 런타임 사용) ---
-keep class com.callradar.app.BuildConfig { *; }

# --- OkHttp(의존성 전이) 선택적 TLS 제공자 : 미포함이라 무시 안전 ---
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
