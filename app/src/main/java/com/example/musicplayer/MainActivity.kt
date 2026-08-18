package com.example.musicplayer

import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingDownload: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
        }
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(MusicBridge(), "MusicBridge")
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 101 && ok) { // 低版本存储权限授予后继续下载
            val p = pendingDownload
            pendingDownload = null
            if (p != null) MusicBridge().download(p.first, p.second)
        } else {
            js("window.__permResult($ok)")
        }
    }

    private fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    /** 简易 HTTP 请求（网易云接口用，原生层无 CORS 限制） */
    private fun http(method: String, url: String, form: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            conn.setRequestProperty("Referer", "https://music.163.com/")
            if (form != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type",
                    if (form.startsWith("{")) "application/json; charset=UTF-8"
                    else "application/x-www-form-urlencoded; charset=UTF-8")
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(form) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.let {
                BufferedReader(InputStreamReader(it, "UTF-8")).use { r -> r.readText() }
            } ?: ""
        } finally {
            conn.disconnect()
        }
    }

    /** 供网页调用的原生桥 */
    inner class MusicBridge {

        @JavascriptInterface
        fun toast(msg: String) = runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }

        /** 申请读取本地音乐权限，结果通过 window.__permResult(true/false) 回调 */
        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread {
                val p = audioPermission()
                if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) {
                    js("window.__permResult(true)")
                } else {
                    requestPermissions(arrayOf(p), 100)
                }
            }
        }

        /** 扫描设备本地音乐，返回 JSON 数组 [{title,artist,album,duration,uri}] */
        @JavascriptInterface
        fun scanMusic(): String {
            val out = JSONArray()
            try {
                val uri = if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val cols = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION
                )
                contentResolver.query(uri, cols, null, null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (c.moveToNext()) {
                        out.put(JSONObject().apply {
                            put("title", c.getString(iT) ?: "未知")
                            put("artist", c.getString(iA) ?: "")
                            put("album", c.getString(iAl) ?: "")
                            put("duration", c.getLong(iD))
                            put("uri", uri.toString() + "/" + c.getLong(iId))
                        })
                    }
                }
            } catch (e: Exception) {
                out.put(JSONObject().put("error", e.message ?: "扫描失败"))
            }
            return out.toString()
        }

        /** 按歌单 ID 获取网易云歌单，返回 {name, tracks:[{id,title,artist,album,dt}]} 或 {error} */
        @JavascriptInterface
        fun getPlaylistDetail(id: String): String {
            return try {
                val pl = JSONObject(
                    http("POST", "https://music.163.com/api/v6/playlist/detail", "id=$id&n=1000&s=0")
                ).optJSONObject("playlist") ?: throw Exception("歌单不存在或需要登录")
                var arr = pl.optJSONArray("tracks") ?: JSONArray()
                // v6 接口对较大歌单只返回前 10 首，用 trackIds 全量补齐（批量歌曲详情一次可取全部）
                val trackIds = pl.optJSONArray("trackIds")
                if (trackIds != null && trackIds.length() > arr.length()) {
                    val sb = StringBuilder("c=[")
                    for (i in 0 until trackIds.length()) {
                        if (i > 0) sb.append(",")
                        sb.append("{\"id\":").append(trackIds.getJSONObject(i).optString("id")).append("}")
                    }
                    sb.append("]")
                    val songs = JSONObject(
                        http("POST", "https://music.163.com/api/v3/song/detail", sb.toString())
                    ).optJSONArray("songs")
                    if (songs != null && songs.length() > arr.length()) arr = songs
                }
                val tracks = JSONArray()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val artists = StringBuilder()
                    val ar = t.optJSONArray("ar") ?: t.optJSONArray("artists")
                    if (ar != null) for (j in 0 until ar.length()) {
                        if (j > 0) artists.append("/")
                        artists.append(ar.getJSONObject(j).optString("name"))
                    }
                    tracks.put(JSONObject().apply {
                        put("id", t.optString("id"))
                        put("title", t.optString("name"))
                        put("artist", artists.toString())
                        put("album", (t.optJSONObject("al") ?: t.optJSONObject("album"))?.optString("name") ?: "")
                        put("dt", if (t.has("dt")) t.optLong("dt") else t.optLong("duration"))
                    })
                }
                JSONObject().put("name", pl.optString("name", "未知歌单")).put("tracks", tracks).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "导入失败").toString()
            }
        }

        /** 获取网易云歌曲真实播放地址（无登录仅免费歌曲可用），失败返回空串 */
        @JavascriptInterface
        fun getSongUrl(id: String): String {
            return try {
                val resp = http("POST", "https://music.163.com/api/song/enhance/player/url", "ids=[$id]&br=320000")
                val data = JSONObject(resp).optJSONArray("data")
                if (data != null && data.length() > 0) data.getJSONObject(0).optString("url", "") else ""
            } catch (e: Exception) { "" }
        }

        /* ==================== 酷狗音乐 ==================== */

        /** 酷狗歌单详情：https://m.kugou.com/plist/list/{id}?json=true */
        @JavascriptInterface
        fun getPlaylistDetailKugou(id: String): String {
            return try {
                val resp = http("GET", "https://m.kugou.com/plist/list/$id?json=true", null)
                val list = JSONObject(resp).optJSONObject("list")?.optJSONObject("list")
                    ?: throw Exception("歌单不存在或已失效")
                val tracks = JSONArray()
                val info = list.optJSONArray("info") ?: JSONArray()
                for (i in 0 until info.length()) {
                    val t = info.getJSONObject(i)
                    val fn = t.optString("filename")
                    val sep = fn.indexOf(" - ")
                    val title = if (sep > 0) fn.substring(sep + 3) else fn
                    val artist = if (sep > 0) fn.substring(0, sep) else ""
                    tracks.put(JSONObject().apply {
                        put("hash", t.optString("hash"))
                        put("title", title)
                        put("artist", artist)
                        put("album", t.optString("album_name"))
                        put("dt", t.optLong("duration") * 1000)
                    })
                }
                JSONObject().put("name", "酷狗歌单").put("tracks", tracks).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "导入失败").toString()
            }
        }

        /** 酷狗歌曲播放地址（免费歌曲可用），失败返回空串 */
        @JavascriptInterface
        fun getSongUrlKugou(hash: String): String {
            return try {
                val resp = http("GET", "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash", null)
                val j = JSONObject(resp)
                if (j.optInt("status") != 1 && j.optInt("errcode") != 0) return ""
                j.optString("url").ifEmpty { j.optJSONArray("backup_url")?.optString(0) ?: "" }
            } catch (e: Exception) { "" }
        }

        /* ==================== QQ 音乐 ==================== */

        /** QQ 歌单详情：i.y.qq.com fcg_ucc_getcdinfo_byids_cp.fcg */
        @JavascriptInterface
        fun getPlaylistDetailQQ(id: String): String {
            return try {
                val url = "https://i.y.qq.com/qzone-music/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                    "?type=1&json=1&utf8=1&onlysong=0&nosign=1&disstid=$id&g_tk=5381&loginUin=0&hostUin=0" +
                    "&format=json&inCharset=GB2312&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
                val j = JSONObject(http("GET", url, null))
                if (j.optInt("code") != 0) throw Exception(j.optString("msg", "歌单获取失败"))
                val cd = j.optJSONArray("cdlist")?.optJSONObject(0) ?: throw Exception("歌单不存在或需要登录")
                val tracks = JSONArray()
                val songlist = cd.optJSONArray("songlist") ?: JSONArray()
                for (i in 0 until songlist.length()) {
                    val t = songlist.getJSONObject(i)
                    val singers = StringBuilder()
                    val sa = t.optJSONArray("singer")
                    if (sa != null) for (k in 0 until sa.length()) {
                        if (k > 0) singers.append("/")
                        singers.append(sa.getJSONObject(k).optString("name"))
                    }
                    tracks.put(JSONObject().apply {
                        put("mid", t.optString("songmid"))
                        put("title", t.optString("songname"))
                        put("artist", singers.toString())
                        put("album", t.optJSONObject("album")?.optString("name") ?: "")
                        put("dt", t.optLong("interval") * 1000)
                    })
                }
                JSONObject().put("name", cd.optString("dissname", "QQ歌单")).put("tracks", tracks).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "导入失败").toString()
            }
        }

        /** QQ 歌曲播放地址（无登录仅免费歌曲可用，参数参考 2025.9 验证可用的开源实现），失败返回空串 */
        @JavascriptInterface
        fun getSongUrlQQ(mid: String): String {
            return try {
                val body = "{\"req_1\":{\"module\":\"vkey.GetVkeyServer\",\"method\":\"CgiGetVkey\"," +
                    "\"param\":{\"filename\":[\"M800$mid.mp3\"],\"guid\":\"10000\",\"songmid\":[\"$mid\"]," +
                    "\"songtype\":[0],\"uin\":\"0\",\"loginflag\":1,\"platform\":\"20\"}}," +
                    "\"loginUin\":\"0\",\"comm\":{\"uin\":\"0\",\"format\":\"json\",\"ct\":24,\"cv\":0}}"
                val data = JSONObject(http("POST", "https://u.y.qq.com/cgi-bin/musicu.fcg", body))
                    .optJSONObject("req_1")?.optJSONObject("data") ?: return ""
                val sip = data.optJSONArray("sip")?.optString(0) ?: ""
                val purl = data.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl") ?: ""
                if (purl.isEmpty()) return ""
                sip + purl
            } catch (e: Exception) { "" }
        }

        /* ==================== 搜歌（网易云 / 酷狗 / QQ 免费歌曲） ==================== */

        /** 按关键词搜索歌曲，channel: netease | kugou | qq，返回 {tracks:[{id|hash|mid,title,artist,album,dt}]} */
        @JavascriptInterface
        fun searchSongs(channel: String, keyword: String): String {
            return try {
                val kw = URLEncoder.encode(keyword, "UTF-8")
                val out = JSONArray()
                when (channel) {
                    "netease" -> {
                        val resp = http("GET",
                            "https://music.163.com/api/search/get/web?csrf_token=&s=$kw&type=1&offset=0&limit=30", null)
                        val songs = JSONObject(resp).optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
                        for (i in 0 until songs.length()) {
                            val t = songs.getJSONObject(i)
                            val artists = StringBuilder()
                            val ar = t.optJSONArray("artists")
                            if (ar != null) for (j in 0 until ar.length()) {
                                if (j > 0) artists.append("/")
                                artists.append(ar.getJSONObject(j).optString("name"))
                            }
                            out.put(JSONObject().apply {
                                put("id", t.optString("id"))
                                put("title", t.optString("name"))
                                put("artist", artists.toString())
                                put("album", t.optJSONObject("album")?.optString("name") ?: "")
                                put("dt", t.optLong("duration"))
                            })
                        }
                    }
                    "kugou" -> {
                        val resp = http("GET",
                            "https://songsearch.kugou.com/song_search_v2?keyword=$kw&page=1&pagesize=30&platform=WebFilter", null)
                        val lists = JSONObject(resp).optJSONObject("data")?.optJSONArray("lists") ?: JSONArray()
                        for (i in 0 until lists.length()) {
                            val t = lists.getJSONObject(i)
                            val fn = t.optString("FileName")
                            val sep = fn.indexOf(" - ")
                            out.put(JSONObject().apply {
                                put("hash", t.optString("FileHash"))
                                put("title", if (sep > 0) fn.substring(sep + 3) else t.optString("SongName"))
                                put("artist", if (sep > 0) fn.substring(0, sep) else "")
                                put("album", t.optString("AlbumName"))
                                put("dt", t.optLong("Duration") * 1000)
                            })
                        }
                    }
                    "qq" -> {
                        val resp = http("GET",
                            "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=30&w=$kw&t=0&format=json&cr=1&g_tk=5381&loginUin=0&hostUin=0&ct=24&cv=0", null)
                        val list = JSONObject(resp).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                            ?: JSONArray()
                        for (i in 0 until list.length()) {
                            val t = list.getJSONObject(i)
                            val singers = StringBuilder()
                            val sa = t.optJSONArray("singer")
                            if (sa != null) for (j in 0 until sa.length()) {
                                if (j > 0) singers.append("/")
                                singers.append(sa.getJSONObject(j).optString("name"))
                            }
                            out.put(JSONObject().apply {
                                put("mid", t.optString("songmid"))
                                put("title", t.optString("songname"))
                                put("artist", singers.toString())
                                put("album", t.optString("albumname"))
                                put("dt", t.optLong("interval") * 1000)
                            })
                        }
                    }
                }
                JSONObject().put("tracks", out).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "搜索失败").toString()
            }
        }

        /** 异步抓取 URL 文本（加载 lx 音源脚本用，不阻塞 JS），完成后回调 window.__srcTextResult(text) */
        @JavascriptInterface
        fun fetchText(url: String) {
            Thread {
                val result = try {
                    val resp = http("GET", url, null)
                    if (resp.isEmpty()) "ERROR:内容为空" else resp
                } catch (e: Exception) {
                    "ERROR:" + (e.message ?: "获取失败")
                }
                runOnUiThread { js("window.__srcTextResult(" + JSONObject.quote(result) + ")") }
            }.start()
        }

        /* ==================== 单曲解析（歌曲链接/ID → 详情 + 播放地址） ==================== */

        /** 网易云单曲详情（song id） */
        @JavascriptInterface
        fun getSongDetailNetease(id: String): String {
            return try {
                val resp = http("GET", "https://music.163.com/api/song/detail?ids=%5B$id%5D", null)
                val songs = JSONObject(resp).optJSONArray("songs")
                if (songs == null || songs.length() == 0) throw Exception("歌曲不存在或已下架")
                val t = songs.getJSONObject(0)
                val artists = StringBuilder()
                val ar = t.optJSONArray("artists") ?: t.optJSONArray("ar")
                if (ar != null) for (j in 0 until ar.length()) {
                    if (j > 0) artists.append("/")
                    artists.append(ar.getJSONObject(j).optString("name"))
                }
                JSONObject().apply {
                    put("id", t.optString("id"))
                    put("title", t.optString("name"))
                    put("artist", artists.toString())
                    put("album", (t.optJSONObject("album") ?: t.optJSONObject("al"))?.optString("name") ?: "")
                    put("dt", if (t.has("dt")) t.optLong("dt") else t.optLong("duration"))
                }.toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "解析失败").toString()
            }
        }

        /** QQ单曲详情（songmid） */
        @JavascriptInterface
        fun getSongDetailQQ(mid: String): String {
            return try {
                val resp = http("GET",
                    "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$mid&tpl=yqq_song_detail&format=json", null)
                val j = JSONObject(resp)
                if (j.optInt("code") != 0) throw Exception("歌曲获取失败")
                val data = j.optJSONArray("data")
                if (data == null || data.length() == 0) throw Exception("歌曲不存在或已下架")
                val t = data.getJSONObject(0)
                val singers = StringBuilder()
                val sa = t.optJSONArray("singer")
                if (sa != null) for (k in 0 until sa.length()) {
                    if (k > 0) singers.append("/")
                    singers.append(sa.getJSONObject(k).optString("name"))
                }
                JSONObject().apply {
                    put("mid", t.optString("songmid").ifEmpty {
                        t.optString("mid").ifEmpty { t.optJSONObject("file")?.optString("media_mid") ?: "" }
                    })
                    put("title", t.optString("name"))
                    put("artist", singers.toString())
                    put("album", t.optJSONObject("album")?.optString("name") ?: "")
                    put("dt", t.optLong("interval") * 1000)
                }.toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "解析失败").toString()
            }
        }

        /** 酷狗单曲信息（hash → 详情+播放地址） */
        @JavascriptInterface
        fun getSongInfoKugou(hash: String): String {
            return try {
                val resp = http("GET", "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash", null)
                val j = JSONObject(resp)
                if (j.optInt("status") != 1 && j.optInt("errcode") != 0) throw Exception("歌曲获取失败")
                val url = j.optString("url").ifEmpty { j.optJSONArray("backup_url")?.optString(0) ?: "" }
                JSONObject().apply {
                    put("hash", hash)
                    put("title", j.optString("songName"))
                    put("artist", j.optString("author_name"))
                    put("album", j.optString("album_name"))
                    put("dt", (if (j.has("timeLength")) j.optLong("timeLength") else j.optLong("duration")) * 1000)
                    put("url", url)
                }.toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "解析失败").toString()
            }
        }

        /** 自定义解析服务（QQMusicapi 兼容）：GET {base}/song/urls?mids={mid}&type=MP3_128，成功返回直链 */
        @JavascriptInterface
        fun getCustomUrl(base: String, mid: String): String {
            return try {
                val u = base.trimEnd('/') + "/song/urls?mids=" + mid + "&type=MP3_128"
                val j = JSONObject(http("GET", u, null))
                if (j.optInt("code") != 0) return ""
                val urls = j.optJSONObject("data")?.optJSONArray("urls")
                if (urls != null && urls.length() > 0) urls.getJSONObject(0).optString("url", "") else ""
            } catch (e: Exception) { "" }
        }

        /** Netease_url 兼容解析：GET {base}/song?id={id}&level=exhigh&type=url → {status,success,data:{code,data:[{url}]}} */
        @JavascriptInterface
        fun getNeteaseResolveUrl(base: String, id: String): String {
            return try {
                val u = base.trimEnd('/') + "/song?id=" + id + "&level=exhigh&type=url"
                val conn = URL(u).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                    conn.setRequestProperty("Referer", base.trimEnd('/') + "/")
                    if (conn.responseCode !in 200..299) return ""
                    val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val j = JSONObject(text)
                    if (!j.optBoolean("success", false)) return ""
                    val data = j.optJSONObject("data") ?: return ""
                    if (data.optInt("code") != 200) return ""
                    val arr = data.optJSONArray("data") ?: return ""
                    if (arr.length() == 0) return ""
                    arr.getJSONObject(0).optString("url", "")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) { "" }
        }

        /* ==================== music.znnu.com 签名解析（HMAC-SHA256 + AES-256-GCM 响应加密） ==================== */
        private var znnuKey: String? = null        // base64 AES-256 密钥
        private var znnuKeyToken: String? = null
        private var znnuKeyExpire: Long = 0
        private var znnuIp: String = ""

        private fun znnuGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                conn.setRequestProperty("X-Referer", "musicParser")
                if (conn.responseCode !in 200..299) return "{}"
                return BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } finally { conn.disconnect() }
        }

        private fun znnuPost(url: String, form: String, keyToken: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                conn.setRequestProperty("X-Referer", "musicParser")
                if (keyToken.isNotEmpty()) conn.setRequestProperty("X-Key-Token", keyToken)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(form) }
                if (conn.responseCode !in 200..299) return "{}"
                return BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
            } finally { conn.disconnect() }
        }

        private fun hmacSha256Hex(key: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** music.znnu.com 解析（实测可用，320k/VIP 取决于该服务）：签名请求 + AES-256-GCM 解密，返回直链或空串 */
        @JavascriptInterface
        fun getZnnuUrl(id: String): String {
            return try {
                // 1. key（带过期缓存）
                if (znnuKey == null || znnuKeyToken == null || System.currentTimeMillis() / 1000 >= znnuKeyExpire - 60) {
                    val kj = JSONObject(znnuGet("https://music.znnu.com/api/key"))
                    if (kj.optInt("code") != 200) return ""
                    val kd = kj.optJSONObject("data") ?: return ""
                    znnuKey = kd.optString("key")
                    znnuKeyToken = kd.optString("keyToken")
                    znnuKeyExpire = kd.optLong("expireAt")
                }
                // 2. ip（缓存）
                if (znnuIp.isEmpty()) {
                    znnuIp = JSONObject(znnuGet("https://music.znnu.com/api/ip")).optString("ip", "")
                }
                // 3. 构造参数 + HMAC-SHA256 签名
                val ts = System.currentTimeMillis() / 1000
                val domain = "music.znnu.com"
                val params = LinkedHashMap<String, String>()
                params["act"] = "song"
                params["id"] = id
                params["level"] = "exhigh"
                params["rawInput"] = id
                params["ip"] = znnuIp
                val signStr = StringBuilder("$ts$domain")
                params.keys.sorted().forEach { k -> signStr.append(k).append("=").append(params[k]) }
                val sig = hmacSha256Hex(
                    "a09d0f3700a279584e1515354fbe08a7ee1c617f919543142fa625b82f1b5ad0",
                    signStr.toString())
                val form = StringBuilder()
                params.forEach { (k, v) ->
                    if (form.isNotEmpty()) form.append("&")
                    form.append(k).append("=").append(URLEncoder.encode(v, "UTF-8"))
                }
                form.append("&signature=").append(sig)
                form.append("&timestamp=").append(ts)
                form.append("&domain=").append(domain)
                val j = JSONObject(znnuPost("https://music.znnu.com/api/song", form.toString(), znnuKeyToken ?: ""))
                if (j.optInt("code") != 200) return ""
                val data = j.optJSONObject("data") ?: return ""
                if (data.optInt("enc") != 1) return data.optString("url", "")
                // 4. AES-256-GCM 解密响应
                val keyRaw = Base64.decode(znnuKey ?: return "", Base64.NO_WRAP)
                val iv = Base64.decode(data.optString("iv"), Base64.NO_WRAP)
                val ct = Base64.decode(data.optString("ciphertext"), Base64.NO_WRAP)
                val tag = Base64.decode(data.optString("tag"), Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyRaw, "AES"), GCMParameterSpec(128, iv))
                val plain = String(cipher.doFinal(ct + tag), Charsets.UTF_8)
                JSONObject(plain).optString("url", "")
            } catch (e: Exception) { "" }
        }

        /** Meting 解析（默认 api.injahow.cn/meting，实测可用）：{base}/?server={server}&type=url&id={id}&br=320 → 音频直链，失败返回空串 */
        @JavascriptInterface
        fun getMetingUrl(base: String, server: String, id: String): String {
            var conn: HttpURLConnection? = null
            return try {
                val u = base.trimEnd('/') + "/?server=" + server + "&type=url&id=" + id + "&br=320"
                conn = URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                val code = conn.responseCode
                when {
                    code in 300..399 -> { // 302 → 返回 CDN 直链
                        val loc = conn.getHeaderField("Location") ?: return ""
                        if (loc.startsWith("http")) loc else ""
                    }
                    code == 200 -> { // 直接返回音频字节
                        val ct = conn.contentType ?: ""
                        if (ct.contains("audio") || ct.contains("mpeg") || ct.contains("octet-stream")) u else ""
                    }
                    else -> ""
                }
            } catch (e: Exception) { "" } finally {
                conn?.disconnect()
            }
        }

        /** 跟随重定向返回最终 URL（处理 163cn.tv 等短链，手动跟随更可靠；JS/meta 跳转页从 HTML 提取），失败返回空串 */
        @JavascriptInterface
        fun resolveRedirect(url: String): String {
            var cur = url
            var conn: HttpURLConnection? = null
            return try {
                for (i in 0 until 10) {
                    conn = URL(cur).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: return if (cur != url) cur else ""
                        val next = URL(conn.url, loc).toString() // 兼容相对地址
                        conn.disconnect(); conn = null
                        if (next == cur) return next
                        cur = next
                    } else {
                        // 200 且为 HTML 跳转页：尝试从内容里提取歌曲链接（meta/location 跳转）
                        if (i == 0) {
                            val ct = conn.contentType ?: ""
                            if (ct.contains("html") || ct.isBlank()) {
                                val body = try {
                                    BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                                } catch (e: Exception) { "" }
                                val m = Regex(
                                    "(music\\.163\\.com[^\"'<> ]*song\\?id=\\d+)" +
                                        "|(y\\.qq\\.com[^\"'<> ]*songDetail/[0-9A-Za-z]+)" +
                                        "|(kugou\\.com[^\"'<> ]*hash=[0-9A-Fa-f]+)"
                                ).find(body)
                                if (m != null) return m.value
                            }
                        }
                        return if (cur != url) cur else ""
                    }
                }
                cur
            } catch (e: Exception) {
                if (cur != url) cur else ""
            } finally {
                conn?.disconnect()
            }
        }

        /** 抓取 QQ 分享页尝试提取 songmid（数值 songid 链接转 mid），失败返回空串 */
        @JavascriptInterface
        fun getQQMidFromPage(url: String): String {
            return try {
                val resp = http("GET", url, null)
                val m = Regex("songmid.{0,4}([A-Za-z0-9]{10,})").find(resp) ?: return ""
                m.groupValues[1]
            } catch (e: Exception) { "" }
        }

        /* ==================== 汽水音乐 ==================== */

        /** 汽水歌单：接口需登录态且音频 DRM 加密，匿名无法导入播放 */
        @JavascriptInterface
        fun getPlaylistDetailSoda(id: String): String {
            return try {
                val resp = http("POST", "https://beta-luna.douyin.com/luna/playlist/detail", "{\"playlist_id\":\"$id\"}")
                val j = JSONObject(resp)
                if (j.optInt("status_code") != 0 || j.isNull("playlist")) {
                    throw Exception("汽水音乐接口需登录且音频加密，匿名暂无法导入")
                }
                JSONObject().put("name", "汽水歌单").put("tracks", JSONArray()).toString()
            } catch (e: Exception) {
                JSONObject().put("error", e.message ?: "导入失败").toString()
            }
        }

        /* ==================== 下载（仅可解析的免费歌曲；VIP/加密歌曲无法解析，故不会下载） ==================== */

        /** 下载歌曲到 下载/MusicPlayer（Android 10+ 走 MediaStore 无需权限；低版本需存储权限） */
        @JavascriptInterface
        fun download(url: String, name: String) {
            Thread {
                val msg = doDownload(url, name)
                if (msg != null) runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
            }.start()
        }

        private fun doDownload(url: String, name: String): String? {
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "song" }
            // Android 9- 需要存储权限
            if (Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownload = url to safeName
                runOnUiThread {
                    requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
                }
                return null
            }
            return try {
                if (url.startsWith("content://")) {
                    // 本地歌曲：content:// 地址走内容提供器直接复制（不能用 HTTP）
                    val uri = Uri.parse(url)
                    val mime = contentResolver.getType(uri) ?: "audio/mpeg"
                    val fileName = "$safeName.${extFromMime(mime)}"
                    saveToDownloads(fileName, mime) { out ->
                        contentResolver.openInputStream(uri)?.use { ins -> ins.copyTo(out) }
                            ?: throw Exception("无法读取源文件")
                    }
                } else {
                    // 网络歌曲
                    val conn = URL(url).openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
                        when {
                            url.contains("163.com") -> conn.setRequestProperty("Referer", "https://music.163.com/")
                            url.contains("qqmusic") -> conn.setRequestProperty("Referer", "https://y.qq.com/")
                            url.contains("kugou") -> conn.setRequestProperty("Referer", "https://www.kugou.com/")
                        }
                        val code = conn.responseCode
                        if (code !in 200..299) throw Exception("HTTP $code")
                        val fileName = "$safeName.${guessExt(url, conn.contentType)}"
                        saveToDownloads(fileName, conn.contentType ?: "audio/mpeg") { out ->
                            conn.inputStream.use { ins -> ins.copyTo(out) }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (e: Exception) {
                "下载失败：${e.message}"
            }
        }

        /** 保存流到 下载/MusicPlayer（Android 10+ MediaStore；低版本公共目录） */
        private fun saveToDownloads(fileName: String, mime: String, writer: (java.io.OutputStream) -> Unit): String {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MusicPlayer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("无法创建下载文件")
                try {
                    contentResolver.openOutputStream(uri)?.use { out -> writer(out) }
                        ?: throw Exception("无法写入文件")
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return "已下载：下载/MusicPlayer/$fileName"
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "MusicPlayer"
            )
            dir.mkdirs()
            val f = File(dir, fileName)
            f.outputStream().use { out -> writer(out) }
            return "已下载：${f.absolutePath}"
        }

        private fun extFromMime(mime: String): String = when {
            mime.contains("flac") -> "flac"
            mime.contains("ogg") || mime.contains("opus") -> "ogg"
            mime.contains("wav") -> "wav"
            mime.contains("m4a") || mime.contains("mp4") -> "m4a"
            mime.contains("aac") -> "aac"
            else -> "mp3"
        }

        private fun guessExt(url: String, contentType: String?): String {
            val fromUrl = Regex("\\.([a-zA-Z0-9]{2,4})(?:\\?|$)").find(url)?.groupValues?.get(1)?.lowercase()
            if (fromUrl != null && fromUrl in listOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "ape", "wma")) return fromUrl
            val ct = contentType ?: return "mp3"
            return when {
                ct.contains("mpeg") || ct.contains("mp3") -> "mp3"
                ct.contains("mp4") || ct.contains("m4a") -> "m4a"
                ct.contains("flac") -> "flac"
                ct.contains("ogg") || ct.contains("opus") -> "ogg"
                ct.contains("wav") -> "wav"
                ct.contains("aac") -> "aac"
                else -> "mp3"
            }
        }
    }
}