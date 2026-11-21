# AROHI AI ASSISTANT v7.0 — ULTIMATE 100X UPGRADE • Universal JARVIS + Personal AI Companion

**আরোহী** — শুভ্রের (Shubho) ব্যক্তিগত AI সঙ্গী, ভয়েস অ্যাসিস্ট্যান্ট, ডিভাইস কন্ট্রোলার ও অটোমেশন ব্রেইন।

Native Android app (Kotlin). No WebView. minSdk 28 (Android 9), targetSdk 34 — Android 9 থেকে 16+ পর্যন্ত Universal Compatibility (Samsung / Pixel / Xiaomi / Redmi / POCO / Vivo / OPPO / Realme / OnePlus / Motorola / Nothing / ASUS / Sony)।

**Created by "Shù Vrô"** — Telegram: @Shuvrojr07 • Facebook: https://www.facebook.com/shuvromridha77

---

## v7.0 — VERIFIED REPAIR PASS (এই রিভিশনের আপডেট)

বিস্তারিত: [`docs/AUDIT_V7.md`](docs/AUDIT_V7.md) • অফলাইন যাচাই: `python3 tools/static_check.py`

- **OpenAI reasoning-model ফলব্যাক ফিক্স** — `o1/o3/o4` মডেল `max_tokens`/`temperature`
  গ্রহণ করে না; এখন মডেল-পরিবার অনুযায়ী `max_completion_tokens` + persona fold।
  আগে প্রাইমারি মডেল বন্ধ থাকলে প্রতিটি ফলব্যাক 400-এ মরত।
- **Gemini কী আর URL-এ যায় না** — `x-goog-api-key` হেডার (লগ/প্রক্সি লিক বন্ধ)।
- **ব্যাকআপ হার্ডেনিং** — Keystore-এনক্রিপ্টেড কী-ফাইল ব্যাকআপ/ডিভাইস ট্রান্সফার থেকে বাদ;
  মেমরি DB, হিস্টোরি, সেটিংস আগের মতোই ব্যাকআপে থাকে।
- **Provider Enable/Disable** — spec §7/§9 অনুযায়ী বাস্তব সুইচ; কী সেভ রেখেই রাউটিং বন্ধ/চালু।
- **ব্র্যান্ডিং ড্রিফট ফিক্স** — টপ বার আর "4.0" দেখায় না। Developer branding অপরিবর্তিত।
- **অফলাইন স্ট্যাটিক ভেরিফিকেশন গেট** — `tools/static_check.py` (রিসোর্স, viewBinding,
  manifest, Kotlin ব্যালান্স, API ইন্টিগ্রেশন) — ৬/৬ পাস।
- পুরোনো v6.0 সোর্স `backup/ArohiAI-v6.0-source-pristine.zip`-এ অক্ষত (rollback)।

**সততা নোট:** এই রিভিশনটি JDK/Android SDK ছাড়া একটি স্যান্ডবক্সে করা, তাই
`./gradlew assembleDebug` এখানে চালানো হয়নি এবং কোনো APK তৈরি হয়নি। বিল্ড ধাপ §৬-এ
`docs/AUDIT_V7.md`-এ লেখা।

---

## v6.0 — ULTIMATE 100X POWER UPGRADE (এই রিলিজের মূল আপডেট)

ব্যবহারকারীর Screenshot-কে প্রধান UI Reference ধরে **Home ও API Center হুবহু পুনর্নির্মিত**, পুরোনো ১০০% ফাংশন অক্ষত রেখে।

### ১. DUAL-PROVIDER BRAIN — OpenAI + Gemini (স্ক্রিনশট অনুযায়ী)
- **নতুন OpenAI প্রোভাইডার** (`brain/OpenAIClient.kt`): platform.openai.com/api-keys থেকে কী পেস্ট → Save → **কী যাচাই + মডেল অটো-ডিটেকশন + বাস্তব টেস্ট সম্পূর্ণ স্বয়ংক্রিয়** (GET /v1/models + বাস্তব chat/completions রাউন্ড-ট্রিপ, ফলব্যাক চেইনসহ)। মডেল নাম টাইপ করতে হয় না।
- **API Center স্ক্রিনশট-ম্যাচড**: ⬡ OpenAI | ✦ Gemini ট্যাব টগল, প্রোভাইডার কার্ড + **Enabled ✓** ব্যাজ (বাস্তব স্ট্যাটাস), 🔑 API Key ফিল্ড (eye টগল), Save API Key গ্রেডিয়েন্ট বাটন, সবুজ/লাল **Connection Successful/Failed ব্যানার**, — OR —, ⚡ Test Connection + ↻ রিফ্রেশ
- **AI Provider Settings কার্ড**: Default Provider ড্রপডাউন, **Auto Fallback** টগল (একটি ব্যর্থ হলে অন্যটি উত্তর দেয়), **Smart Model Detection** টগল (বাস্তব প্রভাব আছে)
- **নতুন ব্রেইন রাউটার** (`brain/ArohiBrain.kt`): কথোপকথন/অটো-রিপ্লাই/ভিশন/টাস্ক — সব রিকোয়েস্ট ডিফল্ট প্রোভাইডারে যায়; কোটা/কী/নেট ব্যর্থতায় অন্য কনফিগার্ড প্রোভাইডারে অটো-সুইচ; কোনটা উত্তর দিলো তা হোম স্ক্রিনে সৎভাবে দেখা যায়
- ভিশন: Gemini `inline_data` ও OpenAI `image_url` — দুই ফরম্যাটেই ছবি যায়

### ২. MIC AUTO-ON/OFF BUG — সম্পূর্ণ সমাধান (ব্যবহারকারীর রিপোর্টকৃত)
মূল কারণ ছিল: `wakeWordEnabled` ডিফল্ট TRUE + নিঃশব্দ সাইকেলে ০.৮ সেকেন্ড ফাঁকে SpeechRecognizer দ্রুত রিস্টার্ট — মাইক ইন্ডিকেটর এলোমেলো অন/অফ হতো।
- **মাইক এখন ডিফল্টে সম্পূর্ণ বন্ধ** — অ্যাপ খুললেই কখনো জাগে না
- **START AROHI = ব্যবহারকারীর স্পষ্ট অনুমতি** — তার পরেই মাইক ওয়েক-স্ট্যান্ডবাইয়ে যায়; **STOP AROHI = মাইক সম্পূর্ণ বন্ধ**
- **Wake লুপ শান্ত**: প্রতিটি শোনা সাইকেলের পর ন্যূনতম ২.৫ সেকেন্ড বিরতি — দ্রুত অন/অফ চক্র ও ব্যাটারি খরচ নেই
- **রিবুটেও নিয়ম এক**: STOP করা অবস্থায় BootReceiver সার্ভিস আর জাগায় না
- UI-তে সৎ অবস্থা: DORMANT ("আরোহী" বললে জাগবো) কখনোই ভুয়া "Listening" দেখায় না

### ৩. BENGALI-ONLY GUARD (ব্যবহারকারীর রিপোর্ট: "সে ইংরেজিতে বলছে")
- **নতুন** `brain/BengaliGuard.kt`: প্রতিটি উত্তরের লিপি যাচাই — ইংরেজি-প্রধান উত্তর ধরা পড়লে **একবার জোরালো বাংলা-নির্দেশসহ পুনঃরিকোয়েস্ট**; প্রযুক্তিগত শব্দ (YouTube, Wi-Fi) বৈধ
- TTS ভাষা অগ্রাধিকার bn-BD → bn-IN → bn; System Prompt-এ ইতিমধ্যে শক্ত বাংলা-কেবল নিয়ম

### ৪. HOME স্ক্রিন — স্ক্রিনশট অনুযায়ী Pixel-Perfect
- **অ্যানিমে অ্যাভাটার হিরো** (নতুন জেনারেটেড আর্ট) + "Hello Boss! আমি AROHI…" স্পিচ বাবল
- **লাইভ ওয়েভফর্ম বার**, **৪টি বাস্তব স্ট্যাটাস পিল** (অনলাইন/স্মৃতি সংখ্যা/ব্যাটারি %/সুরক্ষা — সব সত্যিকারের ডেটা)
- **তিন বৃত্ত**: Start AROHI (নীল) / AI ব্রেইন-টেস্ট (বেগুনি) / Stop AROHI (লাল)
- **Listening পিল** — প্রকৃত ConvState অনুযায়ী; **৬টি ফাংশন বাটন** (Assistant/Device/Memory/Automation/Vision/More — সব বাস্তব নেভিগেশন)
- **👑 Created by "Shù Vrô" ❤️** ব্র্যান্ডিং স্ক্রিনশটের মতোই
- v5-এর সব: সম্পূর্ণ স্ট্যাটাস গ্রিড, Quick Actions, Recent Activities, Setup Wizard — নিচে স্ক্রলে অক্ষত

### ৫. ১২তম এজেন্ট + আরও
- **System Diagnostics Agent** (সিস্টেম ডায়াগনস্টিকস) — মোট **১২ এজেন্ট**; "স্ট্যাটাস/health/রিপোর্ট" কোয়েরি এখন এই এজেন্ট ধরে
- System Health-এ **OpenAI ব্রেইন** আইটেম; ব্রেইন স্ট্যাটাস-লাইনে দুই প্রোভাইডারের বাস্তব অবস্থা + শেষ উত্তরদাতা

### v6 এ অক্ষত রাখা পুরোনো সব ক্ষমতা
Wake word ("আরোহী/Hey Arohi/হেই আরোহী") • ২৪/৭ ফোরগ্রাউন্ড সার্ভিস • নোটিফিকেশন বাংলা ঘোষণা + অটো-রিপ্লাই (Inline Reply ৩-ধাপ পাইপলাইন) • **নোটিফিকেশন টেক্সট চ্যাট (অ্যাপের বাইরে থেকে গল্প)** • ফাইল/গ্যালারি অ্যাক্সেস • লং চ্যাট (২৪-টার্ন কনটেক্সট + সেশন টাইমআউট কনফিগারেবল) • টোন মিররিং (ToneTracker — শুভ্রের গলার সুর বুঝে একই ভঙ্গিতে উত্তর) • স্ক্রিন টাচ অটোমেশন • ভিশন/OCR/QR • Smart Home (MQTT) • PC ব্রিজ • মেমরি সিস্টেম (Room, এনক্রিপ্টেড) • App Lock/বায়োমেট্রিক • Emergency Stop • ElevenLabs ভয়েস

---

## v4.1 — GEMINI AUTO-CONNECTION (এই রিলিজের মূল আপডেট)

**এখন আর মডেল নাম জানা বা লেখার দরকার নেই।** Google AI Studio থেকে নেওয়া Gemini API Key সরাসরি API CENTER-এ Paste করলেই (১.২ সেকেন্ডের মধ্যে, স্বয়ংক্রিয়ভাবে):

1. **কী পরিষ্কার** — পেস্টের সাথে আসা স্পেস/নিউলাইন/কোট/"Bearer"/"key=" নিজেই মুছে ফেলে
2. **ফরম্যাট যাচাই** — AIza… আকৃতি নেটওয়ার্ক কলের আগেই যাচাই
3. **কী যাচাই (AUTH)** — সরাসরি Google `ListModels` কল: HTTP 400 API_KEY_INVALID / 401 / 403 / 429 — প্রতিটির জন্য নির্দিষ্ট বাংলা কারণ + সমাধান
4. **Compatible Model অটো-নির্বাচন (DISCOVER)** — এই কী-র Supported Model List থেকে `generateContent`-সক্ষম সব মডেল র‍্যাঙ্ক করে সেরাটি বাছে (gemini-2.0-flash → 2.5-flash → flash-lite → 1.5-flash …) — **ব্যবহারকারীকে কিছুই সিলেক্ট/টাইপ করতে হয় না**
5. **বাস্তব কানেকশন টেস্ট (TEST)** — আসল `generateContent` রাউন্ড-ট্রিপ; প্রথম মডেল 404/model-not-found দিলে **ফলব্যাক চেইনে নিজেই ঘুরে যায়** (৫টি পর্যন্ত) এবং যেটি কাজ করে সেটি সেভ করে
6. **প্রকৃত Success Status** — সফল হলেই কেবল "কানেক্টেড ✓ — মডেল: …" দেখায়; ব্যর্থ হলে নির্দিষ্ট HTTP কোডসহ কারণ ও সমাধান

অতিরিক্ত v4.1 সংশোধন:
- **রিকোয়েস্ট-টাইম সেলফ-হিলিং**: চলার পথে মডেল বন্ধ হলে প্রতিটি chat রিকোয়েস্ট নিজেই ফলব্যাক চেইনে ঘুরে যায় — HTTP 404/Invalid Model আর কখনো মারাত্মক নয়
- **অফলাইনে কী সেভ হয়, ইন্টারনেট ফিরলে অটো-কানেক্ট** (one-shot reconnect hook)
- **Android 14+ ক্র্যাশ ফিক্স**: মাইক অনুমতি ছাড়া সার্ভিস শুরু হলে `FOREGROUND_SERVICE_TYPE_MICROPHONE` SecurityException আর ক্র্যাশ করে না (graceful degradation)
- **TTS callback লস ফিক্স**: ইঞ্জিন init-এর আগে কথা বলতে গেলেও কথোপকথন লুপ আর আটকায় না
- **অ্যাপ আইকন**: অফিসিয়াল AROHI AI পোস্টার (Shù Vrô ক্রেডিটসহ) থেকে তৈরি — legacy (রাউন্ডেড+রাউন্ড) + adaptive (৫ ঘনত্বে fg) সম্পূর্ণ সেট
- Settings স্ক্রিন থেকেও ম্যানুয়াল মডেল ইনপুট সরানো হয়েছে — "সেভ কী + অটো-কানেক্ট" এক ট্যাপে সব

## v4.0 — আগের সমস্যাগুলোর সম্পূর্ণ সমাধান (সব পুরনো ফিচার অক্ষত)

| v3-তে সমস্যা | v4 সমাধান |
|---|---|
| "API key কানেক্ট হচ্ছে না" | **GeminiClient v4**: কী-ফরম্যাট ভ্যালিডেশন → বাস্তব টেস্ট → ব্যর্থ হলে **ListModels ডিসকভারি** — পুরনো/বাতিল মডেল নাম অটো ঠিক হয়ে যায়; প্রতিটি ব্যর্থতার **সঠিক কারণ + সমাধানের পরামর্শ** বাংলায় |
| "কোনটা কানেক্টেড কোনটা ডিসকানেক্ট বোঝা যাচ্ছে না" | নতুন **API CENTER** স্ক্রিন: প্রতিটি প্রোভাইডারের আলাদা কার্ড — Get Key রিডাইরেক্ট, সেভ, বাস্তব টেস্ট, মডেল অটো-ফিক্স, চালু/বন্ধ, এরর ডিটেইল |
| নেটওয়ার্ক সবসময় বিচ্ছিন্ন দেখাচ্ছিল | **NetworkMonitor v4**: ৩টি স্বাধীন প্রোব এন্ডপয়েন্ট + **exponential backoff** (১০→২০→৪০→৬০ সে) + Wi-Fi/মোবাইল ডেটা লেবেল + ফিরলে **অটো-রিকাভারি কলব্যাক** |
| ব্যাকগ্রাউন্ডে ভয়েস শুনতে পারছিল না | Wake Word **লাইভ টগল** (রিস্টার্ট লাগে না) + ERROR_RECOGNIZER_BUSY/মাইক-কনফ্লিক্টে ধৈর্যশীল রিট্রাই + ওয়াচডগ এখন ইঞ্জিন মুছে গেলে নিজেই ফিরিয়ে আনে |
| নোটিফিকেশনের রিপ্লাই আসলে পাঠাতে পারত না | **AutoReply v4** — ৩-ধাপ বাস্তব পাইপলাইন: ১) **Inline Reply (RemoteInput)** — WhatsApp/Telegram/Messenger/Instagram DM ইত্যাদিতে অ্যাপ না খুলেই OS-অনুমোদিত পাঠানো ২) কথোপকথন খুলে SET_TEXT ৩) SET_TEXT ব্লক হলে **Clipboard PASTE**; পাঠানো না হলে কখনো "Sent" দেখায় না |
| মূল স্ক্রিন থেকে কাজ করত না | START AROHI বাটন এখন অনুমতি প্রি-চেক করে এবং **সার্ভিস সত্যিই চালু হয়েছে কি না যাচাই করে**; ওয়েক ওয়ার্ড কমান্ড সার্ভিস-রেডি হওয়ার আগে এলে কিউ হয়ে কাজ করে |
| বাটন কাজ করছে না | প্রতিটি নতুন স্ক্রিনের প্রতিটি বাটন বাস্তব অ্যাকশনে বাঁধা; স্ট্যাটাস **৭-ধাপ মডেলে** (ACTIVE/INACTIVE/CONNECTING/ERROR/PERMISSION_REQUIRED/NOT_CONFIGURED/UNSUPPORTED) + প্রতিটি সমস্যার পাশে **FIX বাটন** |
| UI যথেষ্ট প্রিমিয়াম নয় | **MaterialToolbar + Drawer Navigation (১১ ডেস্টিনেশন)**, নতুন স্ক্রিনগুলোতে glassmorphism কার্ড, লাইভ এজেন্ট/অ্যাডাপটিভ মোড ইন্ডিকেটর, ব্র্যান্ড-ভিত্তিক ব্যাটারি গাইড |

## v4.0 নতুন সিস্টেম

- **Multi-Agent AI System** (`agents/AgentOrchestrator`): ১১টি এজেন্ট — ব্যক্তিগত সহকারী, রিসার্চ, স্মৃতি, ডিভাইস কন্ট্রোল, ভিশন, অটোমেশন, নোটিফিকেশন, মেসেজিং, সিকিউরিটি, লার্নিং, ফাইল ম্যানেজার; প্রতিটি রিকোয়েস্ট কে সামলাচ্ছে তা হোম স্ক্রিনে লাইভ দেখা যায়
- **Adaptive Intelligence** (`core/AdaptiveEngine`): RAM-ক্লাস + ব্যাটারি + নেটওয়ার্ক দেখে LOCAL / LIGHT / FULL / VISION মোড বেছে নেয়
- **Emergency Stop**: এক কমান্ডে সব টাস্ক/কথা/অটোমেশন/সার্ভিস থামে ("সব থামাও" ভয়েসেও)
- **System Health Dashboard**: ১৮টি কম্পোনেন্টের বাস্তব স্ট্যাটাস + ডিভাইস প্রোফাইল + ম্যানুফ্যাকচারার-ভিত্তিক ২৪/৭ গাইড
- **Automation / Notifications / Messages / Vision স্ক্রিন**: ইঞ্জিন কন্ট্রোল, রুটিন, লাইভ নোটিফিকেশন লিস্ট, প্রতি-মেসেজ রিপ্লাই বাটন (কনফার্মেশনসহ), লাইভ ক্যামেরা ভিশন
- **Support Center**: ডেভেলপার তথ্য + বাস্তব Telegram/Facebook লিংক + রিপোর্ট কপি টুল

## ১. দ্রুত বিল্ড (Fast Build)

### Android Studio (recommended)
1. Android Studio Hedgehog (2023.1.1)+ খুলুন (bundled JDK 17/21 যথেষ্ট)
2. `File → Open` → এই `ArohiAI` ফোল্ডার সিলেক্ট করুন
3. Gradle sync শেষ হলে `Run ▶` (ডিভাইস/এমুলেটর কানেক্ট করুন)

### Command line
```bash
cd ArohiAI
./gradlew assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
```
- Requires **JDK 17+** full JDK (`java -version` + `javac -version` চেক করুন; Android Studio-র bundled JDK চলবে)
- এই রেপোর `gradle.properties`-এ `org.gradle.java.home=/home/z/jdk17` সেট করা আছে — নিজের মেশিনে অপ্রয়োজনীয় হলে ওই লাইন মুছে দিন
- Gradle wrapper (jar সহ) অন্তর্ভুক্ত — আলাদা Gradle ইনস্টল লাগবে না
- Android Studio প্রথমবার sync-এ `local.properties` নিজে থেকেই বানিয়ে নেবে

### Release APK
```bash
./gradlew assembleRelease      # আপনার নিজের keystore যোগ করুন (app/build.gradle → signingConfig)
```

## ২. প্রথম চালুকালীন সেটআপ (অবশ্যই দরকার)

| ধাপ | কোথায় | কেন |
|---|---|---|
| Gemini API Key | **API CENTER → "Get API Key"** → [aistudio.google.com/apikey](https://aistudio.google.com/apikey) | আরোহীর ব্রেইন |
| ElevenLabs Key + Voice ID (ঐচ্ছিক) | API CENTER → ElevenLabs বাটন | ন্যাচারাল মেয়ে-কণ্ঠ TTS |
| মাইক্রোফোন / নোটিফিকেশন লিসেনার / অ্যাক্সেসিবিলিটি / ব্যাটারি | HOME → "সব অনুমতি এক ট্যাপে" উইজার্ড | ভয়েস, নোটিফিকেশন, রিপ্লাই, ২৪/৭ |
| ২৪/৭ রাখতে | SYSTEM HEALTH → আপনার ব্র্যান্ডের গাইড ফলো করুন | OEM-কিলিং এড়াতে |

## ৩. আর্কিটেকচার

```
app/src/main/java/com/arohi/ai/
├── ArohiApp.kt / MainActivity.kt (DrawerLayout: ১১ ডেস্টিনেশন + Emergency)
├── core/       ArohiBus, SettingsStore, SecureStore, NetworkMonitor v4 (multi-probe),
│               ApiHealth v4 (৭-স্ট্যাটাস), PermissionHelper, AdaptiveEngine, EmergencyStop
├── agents/     AgentOrchestrator (১১ এজেন্ট, রাউটিং + রিডিনেস)
├── brain/      GeminiClient v4 (key validation, ListModels self-heal), SystemPromptBuilder, IntentParser
├── voice/      SttManager, TtsManager (ElevenLabs+device), VadMeter, WakeWordEngine v4 (live toggle),
│               ConversationEngine
├── commands/   CommandRouter (confirmation gate + agent routing), ActionExecutor (40+ real actions)
├── actions/    DeviceController, AppLauncher, PeopleHelper
├── net/        InternetClients (Open-Meteo, Google News RSS, Wikipedia — keyless)
├── memory/     Room DB: memories, chat_messages, routines, activity_log (+export/import)
├── services/   ArohiForegroundService (watchdog v4), ArohiNotificationListener (Inline Reply capture),
│               ArohiAccessibilityService (SET_TEXT→PASTE→gesture), BootReceiver, RoutineReceiver
├── automation/ TaskEngine, RoutineScheduler, AutoReplyEngine v4 (3-path real send), EmotionEngine
├── proactive/  ProactiveEngine (time-aware care lines)
├── vision/     CameraVisionManager, VisionAnalyzer, ScreenCaptureManager
├── smarthome/  MqttManager (Paho) + SmartHomeStore
├── pc/         PcBridgeClient (TCP + AES-GCM)
├── security/   AppLockManager (Biometric+PIN hash)
└── ui/         ArohiCoreView, AvatarView, WaveformView + Home, Assistant, Device, Memory, Settings,
                ApiCenter, SystemHealth, Automation, Vision, Notifications, Messages, SupportCenter,
                ChatAdapter, UiKit
```


## ৪. ফিচার স্ট্যাটাস — সম্পূর্ণ সৎ রিপোর্ট (কোনো ভুয়া স্ট্যাটাস নেই)

### ✅ সম্পূর্ণ কার্যকর (real, working)
- **Voice loop**: মাইক → STT → Gemini → TTS → আবার শোনা; Stop button; barge-in ("থামো")
- **Wake word**: "আরোহী / hey arohi" — foreground service-এ চলে (Battery saver এ থামতে পারে)
- **Gemini brain**: বাংলা/Banglish/English, context memory, JSON action protocol, retry
- **Emotion engine**: রাগ/খুশি/অভিমান — কঠিন কথায় মন খারাপ করে, sorry বললে ভালো হয়; avatar-এ রঙ বদলায়
- **Proactive**: সকাল/রাতের শুভেচ্ছা, দীর্ঘ নীরবতায় যত্নের কথা, ব্যাটারি সতর্কতা — নিজে থেকেই কথা বলে
- **24/7 service**: foreground notification + STOP action + boot auto-start
- **Notification intelligence**: প্রতিটি নোটিফিকেশন ভয়েসে ঘোষণা — কোন অ্যাপ/ইমেইল/মেসেজ/প্রেরক
- **Auto-reply**: ASK mode (ড্রাফট বলে অনুমতি নেয়) ও AUTO mode (Accessibility দিয়ে টাইপ+সেন্ড) — WhatsApp/Messenger/Telegram ইত্যাদিতে; ব্যর্থ হলে সৎ বার্তা
- **Device control**: টর্চ, ব্রাইটনেস*, ভলিউম, ব্যাটারি+হেলথ, স্টোরেজ, RAM, CPU লোড, সময়/তারিখ, অ্যালার্ম, টাইমার, Wi-Fi/BT সেটিংস, স্ক্রিনশট, ফাইল, অ্যাপ লিস্ট/লঞ্চ (*WRITE_SETTINGS অনুমতি)
- **App launcher**: সব ইনস্টল করা অ্যাপ খোলা; না থাকলে সৎভাবে প্লে স্টোরে নিয়ে যায় (নিজে ইনস্টল করা Play পলিসিতে নিষিদ্ধ)
- **Internet**: Weather (Open-Meteo), বাংলা News (Google News RSS), Wikipedia, Google search, YouTube, Maps, যেকোনো URL
- **Communication**: কন্টাক্ট সার্চ, কল (CALL_PHONE+অনুমতি), SMS/WhatsApp/Email **ড্রাফট** (সাইলেন্ট সেন্ড Android নিয়মে অসম্ভব)
- **Memory**: Room DB — সার্চ/যোগ/এডিট/ডিলিট/এক্সপোর্ট-ইমপোর্ট/অন-অফ; শুভ্রর নাম স্থায়ীভাবে জানে
- **Vision**: CameraX লাইভ ভিশন → Gemini Vision বর্ণনা; ছবি পিক করে বিশ্লেষণ; OCR — ML Kit Latin (অন-ডিভাইস) + বাংলা/অন্য লিপির জন্য Gemini Vision OCR (ML Kit-এর bundled মডেলে বাংলা নেই — এটিই বাস্তবতা)
- **Screenshot**: MediaProjection (প্রতিবার সিস্টেম ডায়ালগ — এটাই Android নিয়ম) → Pictures/Arohi
- **Automation**: মাল্টি-স্টেপ প্ল্যান, লাইভ প্রোগ্রেস, retry, cancel, history log; রুটিন exact alarm
- **Smart Home**: বাস্তব MQTT (Paho) — Arduino/ESP32/ESPHome/Home Assistant; বাস্তব কানেকশন স্ট্যাটাস
- **Security**: Biometric/PIN app lock (hashed), encrypted key store, প্রাইভেসি ড্যাশবোর্ড, অ্যাক্টিভিটি লগ
- **Offline mode**: ইন্টারনেট না থাকলে স্পষ্ট ঘোষণা + লোকাল কমান্ড/স্মৃতি চালু থাকে

### ⚠️ আংশিক / প্ল্যাটফর্ম সীমাবদ্ধ (সৎভাবে চিহ্নিত)
- **Screen-touch automation**: AccessibilityService দিয়ে টাপ/সোয়াইপ/টাইপ করা যায়, কিন্তু প্রতিটি অ্যাপের UI ভিন্ন — সেন্ড-বাটন না পেলে আরোহী সৎভাবে বলে ব্যর্থ হয়েছে
- **Screen monitoring**: MediaProjection প্রতি সেশনে ব্যবহারকারীর সম্মতি চায় (Android বাধ্যতামূলক); চলমান মনিটরিং SERVICE ট্যাবে টগলের সাথে যুক্ত
- **App নিজে ইনস্টল**: সম্ভব নয় (Play পলিসি) — প্লে স্টোর সার্চ খুলে দেয়
- **২৪/৭ গ্যারান্টি**: aggressive OEM (Samsung/Xiaomi) background মেরে ফেলতে পারে — Unrestricted সেট করা বাধ্যতামূলক; START_STICKY + boot receiver দিয়ে পুনরুদ্ধার হয়

### 🔌 PC Integration (architecture ready — PC companion অ্যাপ লাগবে)
Protocol: `TCP host:port → [4B length][AES-256-GCM(JSON)]`, JSON `{"token","command","ts"}`, response `{"ok","data"}`.
Commands: `launch:app`, `type:text`, `screenshot`, `sysinfo`, `cpu`, `ram`, `shutdown`, `restart`, `file:path`.
Android side fully implemented; PC অ্যাপ ছাড়া সৎভাবে "PC অ্যাপ প্রয়োজন" বলে — কখনো ভুয়া success দেখায় না।

### 🏠 MQTT payload (ESP32/Arduino ফার্মওয়্যার উদাহরণ)
```
topic:  arohi/home/lamp/set
payload: ON | OFF | BRIGHT_70 | SPEED_2 | {"color":{"r":255,"g":80,"b":120}}
telemetry subscribe: arohi/home/# (temperature/sensor JSON যা পাঠাবেন দেখাবে)
```

## ৫. ভুল বার্তা (মূল প্রজেক্টের Hinglish ভয়েস-প্যাক সংরক্ষিত)
- Gemini ব্যর্থ → "Sir, Gemini se response nahi aa raha."
- ভয়েস সার্ভিস বন্ধ → "Sir, voice service temporarily unavailable hai."
- মাইক পারমিশন নেই → "Microphone permission required hai."
- ইন্টারনেট নেই → "Internet connection check kijiye."

## ৬. পরিচিত সীমাবদ্ধতা / পরামর্শ
- STT-র জন্য ডিভাইসে Google speech service লাগবে (বেশিরভাগ ফোনে থাকে)
- TTS চলার সময় মাইক স্পিকার-ইকো শুনতে পারে — barge-in শুধু "থামো/stop" শব্দে ট্রিগার হয়
- ElevenLabs ব্যবহারে API quota লাগে; ফ্রি টিয়ারে ডিভাইস TTS ব্যবহার করুন
- Gemini মডেল SETTINGS থেকে বদলানো যায় (ডিফল্ট `gemini-2.0-flash`)
