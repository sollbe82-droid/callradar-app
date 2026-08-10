package com.callradar.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * [명세서 스펙 v31+] 회사 프로필 = 실시간 예상급여의 파라미터.
 *
 * 급여식은 [회사 × 근무형태(일차/주간/야간/격일)] 조합으로 갈린다. 같은 기사도 회사·근무형태가
 * 바뀌면 명세서 구조가 통째로 달라지므로 조합을 키로 저장한다.
 *
 * 예상 기사 몫 = (총매출 − 사납금×근무일수) × 초과율 − 가스비(기사부담일 때) + 기본급/수당
 *
 * 콜레이더는 총매출(운행기록)·근무일수(근무세션)·가스비(영수증)를 이미 보유 → 프로필만 알면
 * 명세서가 오기 전에도 매일 예상급여를 계산한다. 명세서는 검증·보정용.
 *
 * 저장: prefs "company_profiles"(JSON 배열), 활성 키 "active_profile"("회사|근무형태").
 * 입력원: ①기사 입력 ②명세서 역산 ③계산방법 인쇄 OCR.
 */
data class CompanyProfile(
    val company: String,
    val workType: String,        // 일차 | 주간 | 야간 | 격일 | ""
    val sanapDaily: Int,         // 일 사납금(기준입금)
    val gasBearer: String,       // "기사" | "회사"
    val overRate: Double,        // 초과율(기본 1.0 = 100%)
    val baseSalary: Int          // 기본급/수당(도급 아닌 회사) — 없으면 0
) {
    fun key() = "${company.trim()}|${workType.trim()}"

    fun label(): String = buildString {
        append(if (company.isBlank()) "회사 미지정" else company)
        if (workType.isNotBlank()) append(" · $workType")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("company", company); put("workType", workType); put("sanapDaily", sanapDaily)
        put("gasBearer", gasBearer); put("overRate", overRate); put("baseSalary", baseSalary)
    }

    /**
     * 예상 기사 몫. 초과율은 '사납금 초과분(순 운송수입)'에 적용한다.
     * @param totalRevenue 기간 총매출(운행기록)
     * @param workedDays   해당 기간 실제 근무일수
     * @param driverGasCost 기사부담 가스비 총액(회사부담이면 무시)
     */
    fun expectedShare(totalRevenue: Int, workedDays: Int, driverGasCost: Int): Int {
        val sanapTotal = sanapDaily * workedDays
        val excess = totalRevenue - sanapTotal              // 사납 초과분(순 운송수입)
        val shareOfExcess = (excess * overRate).toInt()
        val gas = if (gasBearer == "기사") driverGasCost else 0
        return shareOfExcess - gas + baseSalary
    }

    companion object {
        private const val KEY_LIST = "company_profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_SEED_CLEARED = "company_seeds_cleared_v34"

        // [프로필격리] 예전 버전이 자동 주입했던 예시. 이제 신규 주입은 없고, '손 안 댄 잔여분' 1회 정리에만 사용.
        private val LEGACY_SEEDS = listOf(
            CompanyProfile("다연상운", "일차", 110000, "기사", 1.0, 0),
            CompanyProfile("서원택시", "주간", 140000, "회사", 1.0, 0)
        )

        private fun fromJson(o: JSONObject) = CompanyProfile(
            company = o.optString("company", ""),
            workType = o.optString("workType", ""),
            sanapDaily = o.optInt("sanapDaily", 0),
            gasBearer = o.optString("gasBearer", "기사"),
            overRate = o.optDouble("overRate", 1.0),
            baseSalary = o.optInt("baseSalary", 0)
        )

        /** 전체 프로필. 기사가 직접 추가/명세서 스캔한 것만 반환(예시 자동 주입 없음 — 프로필 격리). */
        fun all(prefs: SharedPreferences): List<CompanyProfile> {
            maybeClearLegacySeeds(prefs)
            val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) { emptyList() }
        }

        /**
         * [프로필격리] 예전 버전이 자동 주입한 예시(다연상운·서원택시)가 '손 안 댄 채' 남아있으면 1회만 제거.
         * 기사가 직접 고쳤거나 자기 회사를 새로 추가했으면(=예시 시드의 부분집합이 아니면) 전부 보존.
         */
        private fun maybeClearLegacySeeds(prefs: SharedPreferences) {
            if (prefs.getBoolean(KEY_SEED_CLEARED, false)) return
            val raw = prefs.getString(KEY_LIST, null)
            if (!raw.isNullOrBlank()) {
                val cur = try {
                    val a = JSONArray(raw); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
                } catch (e: Exception) { emptyList() }
                val seedSet = LEGACY_SEEDS.map { it.toJson().toString() }.toSet()
                val untouched = cur.isNotEmpty() && cur.all { seedSet.contains(it.toJson().toString()) }
                if (untouched) prefs.edit().remove(KEY_LIST).remove(KEY_ACTIVE).apply()
            }
            prefs.edit().putBoolean(KEY_SEED_CLEARED, true).apply()
        }

        private fun saveAll(prefs: SharedPreferences, list: List<CompanyProfile>) {
            val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_LIST, arr.toString()).apply()
        }

        /** 같은 key면 덮어쓰기, 없으면 추가. */
        fun upsert(prefs: SharedPreferences, p: CompanyProfile) {
            val list = all(prefs).toMutableList()
            val idx = list.indexOfFirst { it.key() == p.key() }
            if (idx >= 0) list[idx] = p else list.add(p)
            saveAll(prefs, list)
        }

        fun remove(prefs: SharedPreferences, key: String) {
            saveAll(prefs, all(prefs).filter { it.key() != key })
            if (activeKey(prefs) == key) prefs.edit().remove(KEY_ACTIVE).apply()
        }

        fun activeKey(prefs: SharedPreferences): String = prefs.getString(KEY_ACTIVE, "") ?: ""

        fun setActive(prefs: SharedPreferences, key: String) {
            prefs.edit().putString(KEY_ACTIVE, key).apply()
        }

        fun active(prefs: SharedPreferences): CompanyProfile? {
            val k = activeKey(prefs)
            val list = all(prefs)
            return list.firstOrNull { it.key() == k } ?: list.firstOrNull()
        }

        /**
         * 활성 프로필의 사납금을 기존 '기사 설정'(daily_sanap)에 안전 적용 → 홈 예상급여 카드 반영.
         * (홈 계산 로직은 그대로 두고, 홈이 이미 읽는 키만 기록. 가스/초과율은 기사 설정에서 별도 관리.)
         */
        fun applySanapToSettings(prefs: SharedPreferences, p: CompanyProfile) {
            prefs.edit()
                .putInt("daily_sanap", p.sanapDaily)
                .putString("active_company", p.company)
                .apply()
        }
    }
}
