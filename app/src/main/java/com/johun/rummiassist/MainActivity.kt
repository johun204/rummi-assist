package com.johun.rummiassist

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 설정 안내 화면. 실제 동작은 접근성 서비스(AssistService)가 한다. */
class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "루미 도우미"; textSize = 26f; gravity = Gravity.START })
        status = TextView(this).apply { textSize = 16f; setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        col.addView(Switch(this).apply {
            text = "루미큐브 분석"
            textSize = 20f
            isChecked = prefs.getBoolean(AssistService.KEY_ENABLED, true)
            setPadding(0, pad / 2, 0, pad / 2)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(AssistService.KEY_ENABLED, on).apply(); refresh() }
        })
        col.addView(Button(this).apply {
            text = "1. 접근성 서비스 켜기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "(켜기가 막혀 있으면) 앱 정보 → ⋮ → 제한된 설정 허용"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        col.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
            text = """
                사용법
                • 게임 화면에서 떠 있는 「루미」 버튼을 누르면 실시간 분석이 켜집니다(LIVE). 다시 누르면 꺼집니다.
                  화면이 바뀌면 옛 제안은 바로 지우고, 화면이 멈추면 새 제안을 보여 줍니다.
                • 「루미큐브 분석」 스위치를 끄면 떠 있는 버튼과 분석이 모두 꺼져 배터리를 쓰지 않습니다.
                • 실선 테두리: 손패에서 낼 타일 (번호 = 들어갈 세트)
                • 점선 테두리: 판에서 다른 세트로 옮길 타일
                • 화살표와 반투명 칸: 그 타일을 놓을 자리. 패널에서 세트를 누르면 그 세트만 보여 줍니다.
                • 「조커 사용」을 끄면 손패 조커는 아껴 두고 계산합니다(기본). 조커를 쓰면 더 낼 수 있을 때만 따로 알려 줍니다.
                • 「첫 등록 완료」를 꺼 두면 손패만으로 30점 이상 등록하는 조합을 찾습니다.
                  (판이 비어 있으면 새 게임으로 보고 자동으로 꺼집니다)
                • 「인식 표시」를 켜면 인식한 타일 값을 타일 위에 보여 줍니다.
                • 버튼을 길게 누르면 끄고 표시를 지우며, 끌어서 위치를 옮길 수 있습니다.
                • 화면은 기기 안에서만 분석하며 어디에도 전송하지 않습니다.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean(AssistService.KEY_ENABLED, true)
        status.text = when {
            !isServiceEnabled() -> "❌ 접근성 서비스 꺼짐 — 아래 1번 버튼으로 켜 주세요"
            !enabled -> "⏸ 분석 꺼짐 — 아무 작업도 하지 않습니다"
            else -> "✅ 켜짐 — 게임을 열고 「루미」 버튼을 누르세요"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, AssistService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
