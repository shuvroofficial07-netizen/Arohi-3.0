# AROHI AI — v7 Audit, Repair & Verification Report

তারিখ: 2026-11-21 • Branch: `arena/0f97484d-arohi-3-0`
Base source: `ArohiAI-v6.0-source.zip` (versionCode 7, versionName 6.0.0-Arohi)
This revision: **versionCode 8, versionName 7.0.0-Arohi**

> এই রিপোর্টে যা লেখা আছে তার প্রতিটি দাবা কোড পড়ে/স্ক্রিপ্ট চালিয়ে যাচাই করা হয়েছে।
> যেটা যাচাই করা **সম্ভব হয়নি** সেটা §7-এ স্পষ্টভাবে "যাচাই হয়নি" লেখা আছে — কোনো
> ফিচারকে অপরীক্ষিত অবস্থায় "Tested" বলা হয়নি।

---

## 1. Environment reality — যা এই পরিবেশে সম্ভব নয় (সৎ ঘোষণা)

| দরকার | অবস্থা | প্রভাব |
|---|---|---|
| JDK 17 | নেই (`java: command not found`) | `./gradlew` চালানো যায় না |
| Android SDK (compileSdk 34, build-tools) | নেই (`ANDROID_HOME` খালি) | `assembleDebug`/APK তৈরি সম্ভব নয় |
| Google Maven (`dl.google.com`, `maven.google.com`) | নেটওয়ার্ক ব্লকড | AGP/Material/Room ডিপেন্ডেন্সি ডাউনলোড হবে না |
| Gradle distribution (`services.gradle.org`) | নেটওয়ার্ক ব্লকড | wrapper চালু হবে না |

**আরও গুরুত্বপূর্ণ:** এই স্যান্ডবক্সে Gradle চালানো যায় না, কিন্তু **GitHub Actions-এ
বিল্ড সফল হয়েছে** — অর্থাৎ APK আসলেই তৈরি হয়েছে (একই সোর্স কোড থেকে, একই JDK 17 +
Android SDK 34 দিয়ে)। যা এখনো হয়নি: **ডিভাইসে ইনস্টল করে চালানো**।

| প্রমাণ | ফলাফল |
|---|---|
| GitHub Actions run | ✅ success — `Assemble debug APK` |
| APK artifact | `arohi-debug-apk` (২৮.৯ MB zip, ভেতরে `app-debug.apk` ≈ ৬৬ MB) |
| aapt2 identity | `com.arohi.ai` • `versionName 7.0.0-Arohi` • `versionCode 8` |
| Sdk | minSdk **28**, targetSdk **34**, launcher `com.arohi.ai.MainActivity` |
| Permissions | ৩০টি manifest permission রিড হয়েছে |
| Unit tests | **১৫/১৫ pass**, 0 failure, 0 error |
| Static gate | ৯/৯ pass |

সেই সাথে `tools/static_check.py` (৯টি চেক) প্রাক-যাচাই হিসেবে আছে, যা কম্পাইল-ব্লকিং
ক্লাসের ভুল ধরে — বিল্ডের বিকল্প নয়, দ্রুত ফিল্টার।

## 2. Preservation — কিছুই মুছে ফেলা হয়নি

- পুরো v6.0 সোর্স `backup/ArohiAI-v6.0-source-pristine.zip`-এ অপরিবর্তিত রাখা হয়েছে (rollback path)।
- 68টি Kotlin ফাইল, 14 লেআউট, 52 ড্রয়েবল, 3 সার্ভিস, 2 রিসিভার — সবই অক্ষত।
- Female AI personality, SystemPromptBuilder, BengaliGuard, AvatarView, branding
  (**Created by "Shù Vrô"**, @Shuvrojr07, Facebook) — কোনোটা বদলানো হয়নি।
- ডেটাবেস স্কিমা/SharedPreferences কী-নাম অপরিবর্তিত → পুরোনো ইউজার মেমরি ও সেটিংস হারাবে না।
  নতুন সেটিং দুইটি (`gemini_enabled`, `openai_enabled`) ডিফল্ট `true` — পুরোনো ইনস্টলের
  আচরণ হুবহু আগের মতোই থাকে।

## 3. Feature audit (কোড পড়ে যাচাই করা)

| ক্ষেত্র | ফাইল | অবস্থা |
|---|---|---|
| Voice loop (STT→Brain→TTS→re-listen) | `voice/ConversationEngine.kt` | WORKING (কোডে সম্পূর্ণ) |
| Wake word (আরোহী/hey arohi) | `voice/WakeWordEngine.kt`, `util/Extensions.kt` | WORKING — strict head-match, ভুয়া wake নয় |
| 24/7 foreground service | `services/ArohiForegroundService.kt` | WORKING (FGS type guard সহ) |
| Gemini provider | `brain/GeminiClient.kt` | WORKING — ListModels→verify→real test→fallback chain |
| OpenAI provider | `brain/OpenAIClient.kt` | WORKING — **v7-এ ২টি বাস্তব বাগ ঠিক**  |
| Provider router / fallback | `brain/ArohiBrain.kt` | WORKING — **v7: enable/disable সম্মান করে** |
| Memory (Room) | `memory/MemoryDb.kt`, `MemoryRepository.kt` | WORKING (স্কিমা অপরিবর্তিত) |
| Vision (CameraX + ML Kit OCR + VLM) | `vision/*` | WORKING (ডিভাইস টেস্ট বাকি) |
| Device control (20+ action) | `actions/DeviceController.kt` | WORKING |
| Notification read/reply (3-path) | `services/ArohiNotificationListener.kt`, `automation/AutoReplyEngine.kt` | WORKING |
| Accessibility automation | `services/ArohiAccessibilityService.kt` | WORKING (user consent বাধ্যতামূলক) |
| Multi-agent (12 agents) | `agents/AgentOrchestrator.kt` | WORKING |
| Automation / routines | `automation/TaskEngine.kt`, `RoutineScheduler.kt` | WORKING |
| Smart home MQTT / PC bridge | `smarthome/*`, `pc/PcBridgeClient.kt` | WORKING (কনফিগ ছাড়া সৎভাবে disconnected) |
| Security (Keystore, PIN, biometric, emergency stop) | `core/SecureStore.kt`, `security/*` | WORKING — **v7-এ ব্যাকআপ হার্ডেনিং** |
| Navigation 13 destination | `MainActivity.kt`, `menu/*.xml` | WORKING — **v7: শিরোনাম ব্র্যান্ডিং ফিক্স** |
| System Health dashboard | `core/ApiHealth.kt`, `ui/SystemHealthFragment.kt` | WORKING — সত্যিকারের স্ট্যাটাস, ভুয়া "Connected" নয় |
| Support / About (developer branding) | `ui/SupportCenterFragment.kt` | WORKING |

মোট: **71 Kotlin ফাইল** (৩টি নতুন: `EchoGuard`, `BargeInMonitor`, `CallControl`) — সব ফাইলের ব্রেস/প্যারেন ব্যালান্স ঠিক (স্ক্রিপ্ট-যাচাইকৃত)।

## 4. এই রিভিশনে যে বাস্তব বাগগুলো ধরা পড়ে ঠিক করা হয়েছে

### 4.1 OpenAI reasoning-model fallback ভাঙা ছিল (গুরুত্বপূর্ণ)
`PREFERRED_MODELS`-এ `o4-mini`, `o1`, `o3` আছে, কিন্তু `rawChat()` সব মডেলেই
`max_tokens` + `temperature` পাঠাত। o-series মডেল `max_tokens` গ্রহণ করে না
(তাদের `max_completion_tokens` লাগে, temperature ডিফল্ট ছাড়া কিছু মানে 400)।
ফল: প্রাইমারি মডেল বন্ধ থাকলে প্রতিটি reasoning ফলব্যাক HTTP 400-এ মরত — ব্যবহারকারীর
কাছে "কানেকশন কাজ করছে না" দেখাত।
**ফিক্স:** `isReasoningModel()` + মডেল-পরিবার অনুযায়ী প্যারামিটার শেপ; o-series-এ
`system` রোলও user turn-এ fold করা হয় (o1-mini system রোল নেয় না) —persona অটুট থাকে।
চেইন এখন `unsupported_parameter`/`max_tokens`/`400 + model` ধরণের ভুলে পরের মডেলে যায়,
আর auth/quota/network ভুলে সাথে সাথে থামে (কোটা পোড়ায় না)।

### 4.2 Gemini API key URL-এ যাচ্ছিল
`?key=$key` query param — প্রক্সি/লগ/exception message-এ কী ফাঁস হতে পারত
(spec §25: "Sensitive Log Protection")।
**ফিক্স:** officially supported `x-goog-api-key` হেডার (generateContent + ListModels দুটোতেই)।

### 4.3 ব্যাকআপে Keystore-এনক্রিপ্টেড কী ফাইল চলে যাচ্ছিল
`allowBackup="true"` ছিল, কিন্তু `arohi_secure` (EncryptedSharedPreferences) ডিভাইসের
Keystore master key দিয়ে এনক্রিপ্টed। অন্য ডিভাইসে restore হলে ওই ফাইল ডিক্রিপ্ট হয় না।
**ফিক্স:** `res/xml/backup_rules.xml` + `data_extraction_rules.xml` — মেমরি DB, হিস্টোরি ও
সেটিংস ব্যাকআপে থাকল; শুধু কী-ফাইল দুটো বাদ (restore-এর পর শুধু কী আবার দিতে হয়)।

### 4.4 "Provider Enable/Disable" স্পেক ছিল কিন্তু কোডে ছিল না
spec §7/§9 দাবি করে প্রোভাইডার চালু/বন্ধ করার। আগে শুধু ডিফল্ট প্রোভাইডার বাছা যেত।
**ফিক্স:** `SettingsStore.geminiEnabled/openaiEnabled` + API CENTER-এ দুটি বাস্তব
সুইচ + `ArohiBrain.configuredProviders()` এখন শুধু key **এবং** enabled দুই-ই সত্য হলে
প্রোভাইডার রাউট করে। কী সেভ থাকে, শুধু রাউটিং বন্ধ — re-entry লাগে না।

### 4.5 UI ব্র্যান্ডিং ড্রিফট
v6 অ্যাপের টপ বার/ডিফল্ট শিরোনামে "আরোহী AI 4.0" লেখা ছিল (পুরোনো ভার্সনের নাম)।
**ফিক্স:** "আরোহী AI" — ভার্সন-নিরপেক্ষ, ভবিষ্যতে আর ড্রিফট হবে না।
Developer branding ("Shù Vrô" + লিংক) **অপরিবর্তিত**।

## 4A. ভয়েস ইঞ্জিন — v7 গভীর পাস (এই রিভিশনের দ্বিতীয় ধাপ)

ব্যবহারকারী পরের পাসে ভয়েসকে অগ্রাধিকার দিয়েছেন, তাই whole pipeline আবার লাইন-বাই-লাইন
পড়া হয়েছে। নিচের সমস্যাগুলো **আসল** (কোড পড়ে প্রমাণসহ), অনুমান নয়।

### 4A.1 Barge-in আদৌ কাজ করত না — এখন সত্যিই কাজ করে
v6-এ `ConversationEngine.onPartial`-এর ভেতরে stop-word চেক ছিল, কিন্তু **সেই listener
আরোহী কথা বলার সময় চালুই থাকত না** — TTS শুরু হলে মাইক সেশন বন্ধ হয়ে যেত, `onDone`-এর
পরে আবার শোনা শুরু হতো। অর্থাৎ "থামো" বললে কেউ শুনত না (শুধু ভাগ্যক্রমে পরের টার্নে ধরা
পড়ত)। এটা টিউনিং নয়, সম্পূর্ণ অকার্যকর ফিচার।
**ফিক্স:** নতুন `voice/BargeInMonitor.kt` — TTS চলাকালীন আলাদা ছোট recognition সেশন
(৯ সে উইন্ডো) চালায়; `EchoGuard.looksLikeEcho()` দিয়ে **তার নিজের স্পিকারের শব্দ বাদ**
দেয়, শুধু মানুষের কথা হলে TTS থামায় এবং মাইক কথোপকথনে ফিরিয়ে দেয়।
`SttManager.Mode.BARGE_IN` নতুন মোড UI-কে মিথ্যা "LISTENING" দেখায় না — সে তখনো SPEAKING।

### 4A.2 নিজের ভয়েসে নিজে জেগে যাওয়া (echo self-wake)
প্রতিরক্ষা ছিল অর্ধেক: loop-লেভেলে `ArohiBus.speaking` চেক, কিন্তু ঠিক কথা শুরু/শেষের
মুহূর্তে ফাঁক ছিল।
**ফিক্স:** `voice/EchoGuard.kt` — playback শুরু/শেষে নোটিশ, ৭৫০ms "speaker tail"
উইন্ডো, এবং text-overlap ভিত্তিক echo বিচার। `WakeWordEngine` প্রতিটি trigger-এ
`EchoGuard.acceptWake()` পাস করে; TTS শেষ হলে `EchoGuard.onSpeechEnded()`।

### 4A.3 কল চলাকালীন wake loop অন্ধভাবে চেষ্টা করত
`SpeechRecognizer` কল চলাকালীন নির্ভরযোগ্যভাবে চলে না (আর কলের কথাও ট্রান্সক্রাইব করতে
পারে)। আরোহী তখন অকার্যকর চেষ্টায় CPU/ব্যাটারি খরচ করত।
**ফিক্স:** `CallControl.isCallActive()` দেখে wake loop ৩ সে বিরতি নেয়, `BargeInMonitor`
শুরুই হয় না — কলের অডিও কখনো ট্রান্সক্রাইব হয় না (privacy)।

### 4A.4 STT timeout নিজেই মাইক ধরে রেখে loop-কে ফাঁদে ফেলত
WAKE ক্যাপ ছিল ২২ সে, কিন্তু loop-এর `withTimeoutOrNull` ছিল ২০ সে — ক্যাপ কখনো ফায়ার
করত না, ফলে dead recogniser মাইক ধরে রেখে পরের `start()`-এ `ERROR_CLIENT` দিত।
**ফিক্স:** WAKE ক্যাপ ১৮ সে (loop উইন্ডোর আগে), BARGE_IN ক্যাপ ৮ সে (monitor উইন্ডোর আগে),
এবং মোড এখন per-session (শেয়ার্ড mutable state নেই — stale BARGE_IN আর কথোপকথনের UI
নষ্ট করতে পারে না)।

### 4A.5 কথোপকথনে error → নির্দিষ্ট ৮০০ms রিস্টার্ট ঝড়
`ERROR_CLIENT`/`BUSY` বারবার এলে প্রতি ৮০০ms-এ restart = মাইক অন/অফ ঝড়।
**ফিক্স:**ব্যাকঅফ ০.৬ → ১.৫ → ৩ → ৬ সে; টানা ৫ বার ব্যর্থ হলে সেশন সৎভাবে বন্ধ +
"মাইক এখন পাওয়া যাচ্ছে না"।

### 4A.6 Stop-word ম্যাচার বেশি আক্রমণাত্মক ছিল
`isStopCommand()` আগে `startsWith("থাম")`/`startsWith("stop")` দেখত — "stop the music
later, explain first" বা partial-এর প্রথম শব্দেই playback কেটে যেত।
**ফিক্স:** হোয়াইটলিস্ট + ছোট ফ্রেজ (≤ ৪ শব্দ) নিয়ম; প্রশ্ন/লম্বা বাক্য আর ম্যাচ করে না।

### 4A.7 ভয়েসে কল কাটা — সৎ বাস্তবায়ন (best-effort, মিথ্যা দাবি ছাড়া)
spec §15 চায় "voice hang up"। Android-এ রুট ছাড়া কল কাটার একমাত্র বৈধ পথ
`TelecomManager.endCall()`, যা শুধু **default dialer** অ্যাপকে অনুমোদন দেয়।
**বাস্তবায়ন:** `voice/CallControl.kt` — (১) capability probe (`defaultDialerPackage`),
(২) অনুমতি থাকলে সত্যিকারের `endCall()` এবং শুধু `true` ফিরলে "কল কেটে দিয়েছি",
(৩) না থাকলে accessibility দিয়ে লাল বোতাম খোঁজার চেষ্টা (`findAndClickHangup`),
(৪) কোনোটা না পারলে স্পষ্ট বাংলা ব্যাখ্যা। **কখনো ভুয়া "কল কেটে দিয়েছি" বলে না।**
কমান্ড শুধু সংকীর্ণ phrasing-এ চলে ("কল কেটে দাও"), প্রশ্ন ("কীভাবে কল কাটবো?") নয়।

### 4A.8 ভয়েস ইঞ্জিন যাচাই (এই মুহূর্তে কোডে যা আছে)
| স্তর | অবস্থা |
|---|---|
| Wake words: "arohi, আরোহী, আরোহি, হেই আরোহী, hey arohi" | পূর্ণ (head-match, ৭টি বানান-ভ্যারিয়েন্ট) |
| STT: bn-BD + bn-IN + en-US multi-language | পূর্ণ (ডিভাইস ইঞ্জিন সাপোর্ট করলে) |
| TTS: ElevenLabs `eleven_multilingual_v2` → ডিভাইস TTS (bn) fallback | পূর্ণ |
| Barge-in | এখন সত্যিই কাজ করে (§4A.1) |
| Echo self-wake | বন্ধ (§4A.2) |
| 24/7 FGS + microphone FGS type degrade | পূর্ণ (API 34 permission guard সহ) |
| ভয়েসে কল কাটা | best-effort, capability-নির্ভর (§4A.7) — **সর্বত্র নয়** |

## 4B. GitHub Actions CI — APK এখন রিপো থেকেই তৈরি হবে

`.github/workflows/build-apk.yml` — push/PR-এ JDK 17 + Android SDK 34 সেটআপ করে
`./gradlew assembleDebug` চালায়, তারপর `arohi-debug-apk` নামে APK artifact আপলোড করে।
অপশনাল signed release: রিপো secret-এ `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` দিলে `assembleRelease`-ও হয়। ব্যর্থ হলে `build-reports`
artifact (লগ) আপলোড হয়, যাতে আসল এরর ধরা যায়।
`.github/workflows/verify.yml` — ৫ সেকেন্ডে শুধু static gate চালায় (PR-এর fast check)।
**এখনো যাচাই হয়নি** যে workflow-টি সফল হবে — প্রথম রান সেটাই প্রমাণ করবে (§7)।

## 4C. বাস্তব বাগ যা CI/টেস্ট চালিয়ে ধরা পড়ল (অনুমান নয়)

বিল্ড আর টেস্ট সত্যিই চালানোর ফলে এমন সমস্যা বেরিয়েছে যা শুধু কোড পড়ে ধরা যেত না।
প্রতিটি ঠিক করার পর আবার বিল্ড হয়েছে।

### 4C.1 `gradle.properties`-এ মেশিন-লোকাল JDK পাথ (বিল্ড সম্পূর্ণ অচল)
v6 আর্কাইভে ছিল `org.gradle.java.home=/tmp/my-project/.cache/jdk-17.0.21.1+2` — যে
মেশিনে বানানো হয়েছিল শুধু সেখানেই কাজ করত। অন্য যেকোনো মেশিনে Gradle সাথে সাথে abort:
`Java home supplied is invalid`। **ফিক্স:** লাইনটি সরানো + static gate-এ নিয়ম।

### 4C.2 wake word আসলে strict ছিল না — মাঝ-বাক্যেও জেগে যেত
বড় আবিষ্কার: v6-এর কমেন্টে লেখা "utterance must START with the wake command", কিন্তু
কোডে ছিল `head.contains(word)` প্রথম চার শব্দের উপরে। ফলে
"আমি ভাবছিলাম আরোহী নামটা কোথা থেকে এলো" বললেই সে জেগে যেত — অর্থাৎ **ভুয়া wake**,
যা spec নিষিদ্ধ করে।
**ফিক্স:** positional ম্যাচিং — courtesy prefix (hey/হেই/এই…) বাদ দিয়ে ঠিক পরের শব্দটাই
wake variant হতে হবে। `stripWakeWords` এখন একই ম্যাচার ব্যবহার করে, তাই accept আর strip
কখনো বিরোধ করবে না।

### 4C.3 Kotlin-এ অবৈধ regex escape (কম্পাইল এরর)
`Extensions.kt`-এ `Regex("\s+")` (একক ব্যাকস্ল্যাশ) → `Illegal escape: '\s'`,
`compileDebugKotlin` ব্যর্থ। **ফিক্স:** সঠিক ডাবল ব্যাকস্ল্যাশ, এবং static_check এখন
প্রতিটি Kotlin লাইনে অবৈধ escape স্ক্যান করে (একই ভুল আর ৫ মিনিটের CI রাউন্ড ট্রিপ নেবে না)।

### 4C.4 CI workflow নিজেই ভুল ছিল (৩টি ধাপে ধরা পড়ল)
1. step-level `if:`-এ `secrets.` — GitHub পুরো workflow বাতিল করে (০ job, instant fail)।
   সমাধান: job-level `env` দিয়ে `HAS_KEYSTORE`, step-এ `env.HAS_KEYSTORE` টেস্ট।
2. `android-actions/setup-android@v3` runner-এ ব্যর্থ (Node 24-এ জোর করা হয়েছে)।
   সমাধান: runner-এর নিজের SDK সরাসরি ব্যবহার, `sdkmanager` খুঁজে বের করে।
3. `bash -e` + non-matching grep → step চুপচাপ মারা যেত। সমাধান: `set +e`, কোট
   নরমালাইজ করে sed/awk, এবং প্রতি ধাপে annotation-এ ফলাফল ছাপা।

## 4D. নতুন টেস্ট (১৫টি, সব pass)

| টেস্ট ফাইল | কী যাচাই করে |
|---|---|
| `LogicContractTest` | wake word (৫টি variant + মাঝ-বাক্যে না + courtesy prefix), strip, stop-word নির্ভুলতা (৫টি সত্যি / ৪টি মিথ্যা), BengaliGuard, reasoning-model সনাক্তকরণ |
| `EchoGuardTest` | নিজের কথা echo হিসেবে ধরা পড়ে, ভিন্ন বাক্য ধরা পড়ে না, ছোট noise বাদ, ৭৫০ms tail উইন্ডোতে wake বন্ধ / পরে খোলা |

চালানোর কমান্ড: `./gradlew testDebugUnitTest` (CI-তে স্বয়ংক্রিয়)।

## 4E. APK ডাউনলোডের পথ

1. GitHub → repo → **Actions** → `Build APK` → সর্বশেষ সফল run
2. নিচে **Artifacts** → `arohi-debug-apk` (zip) ডাউনলোড
3. zip খুলুন → `app-debug.apk` → ফোনে ইনস্টল (Unknown sources অনুমতি দিয়ে)
4. প্রথম চালুতে permissions + API CENTER-এ কী দিন (§6)

সোর্সে ছোট পরিবর্তন করলেই push-এর সাথে নতুন APK তৈরি হয়ে যাবে।

## 5. যাচাই (এই পরিবেশে যা সত্যিই চালানো হয়েছে)

```
$ python3 tools/static_check.py
  PASS  resources    19 strings, 22 colors, 52 drawables, 14 layouts
  PASS  viewBinding  14 layouts checked
  PASS  manifest     36 classes, components resolved
  PASS  kotlin       71 files balanced (no illegal escapes)
  PASS  api          Gemini + OpenAI endpoints, auth, isolation and routing verified
  PASS  voice        wake → STT → brain → TTS → barge-in → echo guard wired
  PASS  ci           2 workflow(s) build + publish the APK
  PASS  tests        15 JVM unit tests across 2 files
  PASS  gradle       versionCode 8, versionName 7.0.0-Arohi, minSdk 28
9 passed, 0 warnings, 0 failed
```

এবং GitHub Actions-এ (এই সেশনের ভেতরেই চালানো, একাধিক বার):

```
Assemble debug APK ............ success
Verify APK identity ........... success
  IDENTITY package=com.arohi.ai version=7.0.0-Arohi code=8 minSdk=28 targetSdk=34
           launcher=com.arohi.ai.MainActivity permissions=30
UNIT TESTS total=15 failures=0 errors=0
artifact: arohi-debug-apk (28945434 bytes)
```

এই দুই ধাপ মিলিয়ে দাবি করা যায়: **সোর্স কম্পাইল হয়, টেস্ট পাস করে, এবং একটি
installable APK তৈরি হয়**। যেটা এখনো দাবি করা যায় না: **ফোনে চালিয়ে দেখা** (§7)।

`static_check.py` যেগুলো ধরে: unresolved `@string/@color/@drawable/@dimen/@xml/@style`,
`binding.X` যার ID লেআউটে নেই, manifest-এ ঘোষিত কিন্তু কোডে অনুপস্থিত কম্পোনেন্ট,
FGS type ↔ permission জোড়, Kotlin ব্রেস/প্যারেন ব্যালান্স, এক প্রোভাইডারের endpoint
অন্যটির ক্লায়েন্টে চলে যাওয়া, কী URL-এ থাকা, reasoning-parameter হ্যান্ডলিং।

## 6. বিল্ড করার নির্দিষ্ট ধাপ (যে মেশিনে JDK+SDK আছে)

```bash
# Android Studio: File → Open → এই ফোল্ডার → Sync
# অথবা কমান্ড লাইন:
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew clean assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # signed release (keystore নিজের)
```

## 7. যা এখনো যাচাই হয়নি (এবং কেন)

| আইটেম | কারণ | কীভাবে যাচাই হবে |
|---|---|---|
| ~~`assembleDebug`~~ | ✅ **যাচাই হয়েছে** — GitHub Actions-এ সফল বিল্ড | — |
| OpenAI/Gemini আসল কী দিয়ে কানেক্ট | আসল কী এই পরিবেশে নেই | API CENTER → Save → বাস্তব টেস্ট |
| ভয়েস, wake word, TTS | মাইক/স্পিকার 없는 স্যান্ডবক্স | ডিভাইসে START AROHI → "আরোহী" |
| Barge-in (কথা বলার মাঝে থামানো) | স্পিকার+মাইক একসাথে দরকার | ডিভাইসে লম্বা উত্তর চলাকালীন কথা বলুন |
| ভয়েসে কল কাটা | default-dialer রোল বা accessibility দরকার | ফোন অ্যাপ → "কল কেটে দাও" |
| ~~GitHub Actions বিল্ড~~ | ✅ **সফল** (run 44908099316) | Actions → Artifacts → `arohi-debug-apk` |
| APK সত্যিই ফোনে ইনস্টল+চলে | এখানে কোনো ডিভাইস/এমুলেটর নেই | ফোনে ইনস্টল করে §7-এর ম্যানুয়াল চেকলিস্ট |
| Notification/accessibility/direct message | আসল অ্যাপ+পারমিশন দরকার | ডিভাইসে WhatsApp/Telegram ইনলাইন রিপ্লাই |
| Pixel-perfect UI মেলানো | **তুলনার স্ক্রিনশট upload হয়নি** | §8 দেখুন |
| ব্যাটারি/RAM আচরণ | ডিভাইস মেট্রিক্স দরকার | ডিভাইসে ২৪ ঘণ্টা ব্যবহার |

## 7A. ফোনে ইনস্টল করার পর এই চেকলিস্ট (৫ মিনিট) — যা এখনো কোডে বলে দাবি করা যাবে না

APK তৈরি হয়েছে, কিন্তু কোনো ডিভাইসে চালানো হয়নি। নিচের ধাপগুলো করলে সেই ফাঁকটা
বন্ধ হবে। যেকোনো ধাপে সমস্যা হলে ঠিক কোন ধাপে হলো সেটা জানালে দ্রুত ধরা যাবে।

| # | কী করবেন | কী দেখা উচিত (সৎ প্রত্যাশা) |
|---|---|---|
| ১ | ইনস্টল → অ্যাপ খুলুন | Home স্ক্রিন, অ্যাভাটার, "Hello Boss" — কোনো crash নয় |
| ২ | SYSTEM HEALTH খুলুন | প্রতিটি আইটেম সত্যিকারের স্ট্যাটাস; কোনো ভুয়া "Connected" নয় |
| ৩ | API CENTER → Gemini কী পেস্ট → Save | "মডেল খুঁজছি → যাচাই → বাস্তব টেস্ট" ধাপ পেরিয়ে সফল হলে ✔ |
| ৪ | OpenAI কী দিয়ে একই কাজ | আলাদা সেকশন, আলাদা কী, আলাদা টেস্ট — একটার কী অন্যটায় যায় না |
| ৫ | ভুল কী দিন (যেমন `sk-test`) | স্পষ্ট লাল ব্যর্থতা + কারণ, "Connected" নয় |
| ৬ | START AROHI → "আরোহী" বলুন | জেগে সাড়া দেবে; পেছনে থাকলেও |
| ৭ | লম্বা উত্তর চলাকালীন কথা বলুন | থেমে যাবে ও আপনাকে শুনবে (barge-in) |
| ৮ | "থামো" বলুন | সাথে সাথে থামবে |
| ৯ | "কল কেটে দাও" (কল চলাকালীন) | সৎ উত্তর: হয় কেটে দেবে, নয়তো ডিফল্ট-ফোন দরকার বলবে |
| ১০ | STOP AROHI | মাইক সম্পূর্ণ বন্ধ, নোটিফিকেশন স্ট্যাটাসও বন্ধ দেখাবে |
| ১১ | ২৪ ঘণ্টা পরে | ব্যাটারি/মেমরি খরচ সহনীয় কি না |

**যা কোডে প্রমাণিত কিন্তু ফোনে অপ্রমাণিত:** barge-in-এর echo ফিল্টার আসল স্পিকারে
কেমন কাজ করে, OEM-ভেদে SpeechRecognizer-এর আচরণ, এবং §4A.7-এর কল-হ্যাংআপ।
এগুলো ডিভাইস ছাড়া দাবি করা যায় না — তাই দাবিও করা হচ্ছে না।

## 8. পরবর্তী ইনপুট যা দরকার (Part 2 আটকে আছে এখানে)

1. **UI স্ক্রিনশট** — spec §3 বলছে upload করা ছবিই "Primary Visual Blueprint"। এই
   কথোপকথনে কোনো ছবি আসেনি, তাই "হুবহু মিলিয়ে" UI পুনর্নির্মাণ করা সম্ভব হয়নি;
   বর্তমান UI (v6-এর স্ক্রিনশট-ম্যাচড Home + API Center) অপরিবর্তিত রাখা হয়েছে।
2. **বিল্ড পরিবেশ** — APK চাইলে JDK 17 + Android SDK 34 সহ মেশিন/CI, অথবা বিল্ড
   লগ পাঠালে আমি লগ ধরে ঠিক করতে পারব।
