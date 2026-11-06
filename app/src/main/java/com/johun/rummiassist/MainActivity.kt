package com.johun.rummiassist

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
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
                • 게임 화면에서 떠 있는 「루미」 버튼을 누르면 화면을 분석합니다.
                • 실선 테두리: 손패에서 낼 타일 (번호 = 들어갈 세트)
                • 점선 테두리: 판에서 다른 세트로 옮길 타일
                • 패널의 「첫 등록 완료」를 꺼 두면 손패만으로 30점 이상 등록하는 조합을 찾습니다.
                  (판이 비어 있으면 새 게임으로 보고 자동으로 꺼집니다)
                • 「인식 표시」를 켜면 인식한 타일 값을 타일 위에 보여 줍니다. 패널의 장수와 게임에 표시된 장수가 다르면 다시 분석하세요.
                • 버튼을 길게 누르면 표시를 지우고, 끌어서 위치를 옮길 수 있습니다.
                • 화면은 기기 안에서만 분석하며 어디에도 전송하지 않습니다.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "✅ 서비스 켜짐 — 게임을 열고 「루미」 버튼을 누르세요" else "❌ 서비스 꺼짐 — 아래 버튼으로 켜 주세요"
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, AssistService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
