package com.arohi.ai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Keyless real internet intelligence:
 *  - Weather: Open-Meteo (geocoding + current)
 *  - News: Google News RSS (Bengali)
 *  - Wikipedia: bn.wikipedia REST summary
 */
object InternetClients {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    // ---------------- Weather ----------------
    private val weatherCodes = mapOf(
        0 to "পরিষ্কার আকাশ", 1 to "প্রায় পরিষ্কার", 2 to "আংশিক মেঘ", 3 to "মেঘলা",
        45 to "কুয়াশা", 48 to "ঘন কুয়াশা", 51 to "হালকা গুঁড়ি বৃষ্টি", 53 to "গুঁড়ি বৃষ্টি",
        55 to "ঘন গুঁড়ি বৃষ্টি", 61 to "হালকা বৃষ্টি", 63 to "মাঝারি বৃষ্টি", 65 to "ভারী বৃষ্টি",
        71 to "হালকা তুষারপাত", 73 to "তুষারপাত", 75 to "ভারী তুষারপাত",
        80 to "হালকা বৃষ্টির ঝাপটা", 81 to "বৃষ্টির ঝাপটা", 82 to "প্রবল বৃষ্টি",
        95 to "বজ্রঝড়", 96 to "শিলাবৃষ্টি সহ বজ্রঝড়", 99 to "ভারী শিলাবৃষ্টি"
    )

    suspend fun weather(place: String): String = withContext(Dispatchers.IO) {
        val p = place.ifBlank { "Dhaka" }
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${enc(p)}&count=1&language=bn"
            val geo = get(geoUrl) ?: return@withContext "আবহাওয়া সার্ভারে পৌঁছানো যায়নি"
            val arr = org.json.JSONObject(geo).optJSONArray("results")
                ?: return@withContext "'$p' নামের জায়গা খুঁজে পাইনি"
            val o = arr.getJSONObject(0)
            val lat = o.getDouble("latitude"); val lon = o.getDouble("longitude")
            val name = o.optString("name", p)
            val wx = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto")
                ?: return@withContext "আবহাওয়ার ডেটা আনা যায়নি"
            val cur = org.json.JSONObject(wx).getJSONObject("current")
            val temp = cur.optDouble("temperature_2m", 0.0).toInt()
            val hum = cur.optInt("relative_humidity_2m", 0)
            val code = cur.optInt("weather_code", 0)
            val wind = cur.optDouble("wind_speed_10m", 0.0).toInt()
            val desc = weatherCodes[code] ?: "আবহাওয়া $code"
            "$name-এর আবহাওয়া: $desc, তাপমাত্রা $temp ডিগ্রি, আর্দ্রতা $hum শতাংশ, বাতাস $wind কিলোমিটার/ঘণ্টা"
        } catch (_: Exception) { "আবহাওয়া আনতে সমস্যা হয়েছে — ইন্টারনেট চেক করুন" }
    }

    // ---------------- News ----------------
    suspend fun news(limit: Int = 5): String = withContext(Dispatchers.IO) {
        val xml = get("https://news.google.com/rss?hl=bn&gl=BD&ceid=BD:bn")
            ?: return@withContext "নিউজ আনা যায়নি — ইন্টারনেট চেক করুন"
        val titles = parseRssTitles(xml, limit)
        if (titles.isEmpty()) "এখন কোনো খবর পাইনি"
        else "আজকের সেরা খবর: " + titles.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString(". ") + "."
    }

    fun parseRssTitles(xml: String, limit: Int): List<String> = try {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        var inItem = false; var inTitle = false; val out = mutableListOf<String>()
        while (event != XmlPullParser.END_DOCUMENT && out.size < limit) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) inTitle = true
                }
                XmlPullParser.TEXT -> if (inTitle && inItem) out.add(parser.text.trim())
                XmlPullParser.END_TAG -> when (parser.name) {
                    "item" -> inItem = false
                    "title" -> inTitle = false
                }
            }
            event = parser.next()
        }
        out.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    // ---------------- Wikipedia ----------------
    suspend fun wiki(topic: String): String = withContext(Dispatchers.IO) {
        if (topic.isBlank()) return@withContext "কোন বিষয়ে জানতে চাও বলো তো"
        try {
            val search = get("https://bn.wikipedia.org/w/api.php?action=query&list=search" +
                "&srsearch=${enc(topic)}&format=json&srlimit=1")
            val title = org.json.JSONObject(search).getJSONObject("query")
                .getJSONArray("search").optJSONObject(0)?.optString("title")
                ?: topic
            val sum = get("https://bn.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}")
                ?: return@withContext "উইকিপিডিয়ায় '$topic' খুঁজে পাইনি"
            val extract = org.json.JSONObject(sum).optString("extract")
            if (extract.isBlank()) "উইকিপিডিয়ায় '$topic' সম্পর্কে বিস্তারিত নেই"
            else extract.take(600)
        } catch (_: Exception) {
            try {
                val sum = get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(topic)}")
                val extract = sum?.let { org.json.JSONObject(it).optString("extract") } ?: ""
                if (extract.isBlank()) "উইকিপিডিয়ায় '$topic' খুঁজে পাইনি" else extract.take(600)
            } catch (_: Exception) { "উইকিপিডিয়া আনতে সমস্যা হয়েছে" }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
