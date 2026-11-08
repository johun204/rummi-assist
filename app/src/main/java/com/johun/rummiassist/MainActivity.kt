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
        // 루미큐브 실행 알림을 띄우려면 알림 권한이 필요(Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        // 디버그 빌드 전용: 감지할 게임 패키지 바꾸기(에뮬레이터 시험용)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("gamePackage")?.let { getSharedPreferences("settings", MODE_PRIVATE).edit().putString(AssistService.KEY_GAME_PKG, it).apply() }
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "루미 도우미"; textSize = 26f; gravity = Gravity.START })
        // 버전 + GitHub 릴리스 업데이트
        val current = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        val versionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val versionText = TextView(this).apply { textSize = 14f; text = "현재 버전 v$current · 최신 버전 확인 중…" }
        val updateBtn = Button(this).apply { text = "업데이트"; visibility = android.view.View.GONE }
        versionRow.addView(versionText, LinearLayout.LayoutParams(0, -2, 1f))
        versionRow.addView(updateBtn)
        col.addView(versionRow)
        Updater.latest { rel ->
            when {
                rel == null -> versionText.text = "현재 버전 v$current · 최신 버전을 확인하지 못했어요"
                Updater.compare(rel.version, current) > 0 -> {
                    versionText.text = "현재 버전 v$current · 새 버전 v${rel.version} 이 있어요"
                    updateBtn.visibility = android.view.View.VISIBLE
                    updateBtn.setOnClickListener { Updater.install(this, rel) { msg -> versionText.text = msg } }
                }
                else -> versionText.text = "현재 버전 v$current · 최신 버전이에요"
            }
        }
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
        // 턴 제한 시간: 이 안에 끝낼 수 있는 만큼만 안내(이동 1번 ≈ 2.3초로 계산)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply { text = "턴 제한 시간(초)"; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.EditText(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(prefs.getInt(AssistService.KEY_TURN_SECONDS, 15).toString())
                width = (80 * d).toInt()
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable?) {
                        e?.toString()?.toIntOrNull()?.takeIf { it in 5..600 }?.let { prefs.edit().putInt(AssistService.KEY_TURN_SECONDS, it).apply() }
                    }
                })
            })
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
                • 루미큐브 앱을 실행하면 「어시스턴트를 켤까요?」 알림이 뜹니다. 「켜기」를 누르면 버튼이 나타나고 실시간 분석이 시작됩니다.
                  게임을 나가면 자동으로 꺼지고, 10분 안에 다시 들어오면 묻지 않고 다시 켜집니다.
                • 떠 있는 「루미」 버튼을 누르면 실시간 분석을 켜고 끕니다(LIVE).
                  화면이 바뀌면 옛 제안은 바로 지우고, 화면이 멈추면 새 제안을 보여 줍니다.
                • 「루미큐브 분석」 스위치를 끄면 떠 있는 버튼과 분석이 모두 꺼져 배터리를 쓰지 않습니다.
                • 실선 테두리: 손패에서 낼 타일 (번호 = 들어갈 세트)
                • 점선 테두리: 판에서 다른 세트로 옮길 타일
                • 화살표와 반투명 칸: 그 타일을 놓을 자리. 패널에서 세트를 누르면 그 세트만 보여 줍니다.
                • 「조커 사용」을 끄면 손패 조커는 아껴 두고 계산합니다(기본). 조커를 쓰면 더 낼 수 있을 때만 따로 알려 줍니다.
                • 기본은 「첫 등록 완료」: 판 전체를 재배치해 가장 많이 내는 조합을 찾습니다.
                  첫 등록(30점) 전이면 패널에서 끄세요. 손패만으로 등록하는 조합을 찾고, 3장 이상 내면 다시 켜집니다.
                • 패널은 기본으로 한 줄(최소화)로 보입니다. ▢ 를 누르면 펼쳐지고 – 로 다시 줄어듭니다.
                • 「인식 표시」를 켜면 인식한 타일 값을 타일 위에 보여 줍니다.
                • 버튼을 길게 누르면 끄고 표시를 지우며, 끌어서 위치를 옮길 수 있습니다.
                • 게임 화면 내 아바타 위의 남은 시간(초)을 읽어, 그 안에 끝낼 수 있는 단계만 보여 줍니다.
                  타이머가 안 보이면 「턴 제한 시간」에서 지난 시간을 빼서 계산합니다. 첫 단계도 못 끝낼 것 같으면 뽑기를 권합니다.
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
            else -> "✅ 켜짐 — 루미큐브를 실행하면 알림으로 물어봅니다"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val me = ComponentName(this, AssistService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }
}
