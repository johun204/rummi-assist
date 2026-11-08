package com.johun.rummiassist

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * GitHub 릴리스로 업데이트: 최신 릴리스(태그 vX.Y.Z, APK 첨부)를 확인하고, 내려받아 설치 화면을 연다.
 * 모든 릴리스는 같은 키로 서명돼 있어 기존 앱 위에 그대로 설치된다.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/johun204/rummi-assist/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    class Release(val version: String, val apkUrl: String, val notes: String)

    /** 최신 릴리스를 가져와 메인 스레드로 넘긴다(실패하면 null) */
    fun latest(cb: (Release?) -> Unit) = thread {
        val r = runCatching {
            val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
            Release(j.getString("tag_name").removePrefix("v"), apk.getString("browser_download_url"), j.optString("body"))
        }.getOrNull()
        main.post { cb(r) }
    }

    /** "1.12.1" 같은 버전 비교: a 가 b 보다 새로우면 양수 */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    /** APK 를 내려받아(진행률 콜백) 설치 화면을 연다 */
    fun install(ctx: Context, rel: Release, progress: (String) -> Unit) {
        // 출처를 알 수 없는 앱 설치 권한이 없으면 먼저 그 설정 화면으로
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            progress("이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요")
            return
        }
        thread {
            val ok = runCatching {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "RummiAssist-${rel.version}.apk")
                val c = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 30000 }
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int; var got = 0L; var last = -1
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); got += n
                            val pct = if (total > 0) (got * 100 / total).toInt() else -1
                            if (pct != last && pct % 5 == 0) { last = pct; main.post { progress("내려받는 중 $pct%") } }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            main.post { progress(if (ok) "설치 화면을 열었어요" else "내려받기 실패 — 인터넷 연결을 확인해 주세요") }
        }
    }
}
