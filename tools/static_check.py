#!/usr/bin/env python3
"""
AROHI AI — offline static verification gate
===========================================

The Android build itself needs a JDK + Android SDK + Google Maven access. When
those are unavailable, this script still catches the classes of defect that
would break `assembleDebug`: unresolved resources, view-binding IDs that do not
exist, manifest components pointing at missing classes, broken Kotlin syntax
balance, and API-integration regressions (key in URL, wrong endpoint, wrong
parameter set per model family).

Run:  python3 tools/static_check.py
Exit: 0 = all checks passed, 1 = at least one FAIL.
"""
import os
import re
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
JAVA = os.path.join(MAIN, "java")

fails, warns, oks = [], [], []


def fail(check, msg):
    fails.append((check, msg))


def ok(check, msg=""):
    oks.append((check, msg))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def kt_files():
    return sorted(glob.glob(os.path.join(JAVA, "**", "*.kt"), recursive=True))


def xml_files():
    return sorted(glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True))


# ---------------------------------------------------------------- 1. resources
def check_resources():
    strings, colors, styles, drawables, dimens, bools, integers = (set() for _ in range(7))
    for f in glob.glob(os.path.join(RES, "values", "*.xml")):
        t = read(f)
        strings |= set(re.findall(r'<string name="([^"]+)"', t))
        colors |= set(re.findall(r'<color name="([^"]+)"', t))
        styles |= set(re.findall(r'<style name="([^"]+)"', t))
        dimens |= set(re.findall(r'<dimen name="([^"]+)"', t))
        bools |= set(re.findall(r'<bool name="([^"]+)"', t))
        integers |= set(re.findall(r'<integer name="([^"]+)"', t))
    for d in glob.glob(os.path.join(RES, "drawable*")) + glob.glob(os.path.join(RES, "mipmap*")):
        for f in glob.glob(os.path.join(d, "*")):
            drawables.add(os.path.splitext(os.path.basename(f))[0])
    xmlres = {os.path.splitext(os.path.basename(f))[0]
              for f in glob.glob(os.path.join(RES, "xml", "*.xml"))}
    layouts = {os.path.splitext(os.path.basename(f))[0]
               for f in glob.glob(os.path.join(RES, "layout", "*.xml"))}
    # framework/Material styles that live in libraries, not in our res/values
    framework_style_prefixes = (
        "Theme.Material3", "Theme.Material", "Widget.Material3", "Widget.Material",
        "TextAppearance.Material3", "TextAppearance.Material", "Theme.AppCompat",
        "Widget.AppCompat", "Base.", "Platform.",
    )
    pools = [
        ("@string", strings), ("@color", colors), ("@drawable", drawables),
        ("@dimen", dimens), ("@bool", bools), ("@integer", integers),
        ("@xml", xmlres),
    ]
    for f in xml_files() + [os.path.join(MAIN, "AndroidManifest.xml")]:
        t = read(f)
        for tag, pool in pools:
            for m in re.findall(re.escape(tag) + r"/([A-Za-z0-9_.]+)", t):
                if m not in pool:
                    fail("resources", f"{tag}/{m} missing (used in {os.path.basename(f)})")
        for m in re.findall(r"@style/([A-Za-z0-9_.]+)", t):
            if m in styles or m.startswith(framework_style_prefixes):
                continue
            fail("resources", f"@style/{m} missing (used in {os.path.basename(f)})")
    # R.* references from Kotlin
    for f in kt_files():
        t = read(f)
        for kind, pool in (("string", strings), ("color", colors), ("drawable", drawables),
                           ("dimen", dimens), ("id", None)):
            for m in re.findall(r"\bR\." + kind + r"\.([A-Za-z0-9_]+)", t):
                if pool is not None and m not in pool:
                    fail("resources", f"R.{kind}.{m} missing (used in {os.path.basename(f)})")
    ok("resources", f"{len(strings)} strings, {len(colors)} colors, {len(drawables)} drawables, "
                    f"{len(layouts)} layouts")


# ------------------------------------------------- 2. viewBinding id contracts
def camel(s):
    parts = s.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def check_view_binding():
    ids = {}
    for f in glob.glob(os.path.join(RES, "layout", "*.xml")):
        name = os.path.splitext(os.path.basename(f))[0]
        ids[name] = set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', read(f)))
    for f in kt_files():
        t = read(f)
        m = re.search(r"(Fragment|Activity|Dialog|Sheet)[A-Za-z]*Binding", t)
        if not m:
            continue
        btype = m.group(0)
        if btype + ".inflate" not in t:
            continue
        cls = os.path.splitext(os.path.basename(f))[0]
        stem = re.sub(r"(Fragment|Activity|Dialog)$", "", cls)
        snake = re.sub(r"(?<!^)(?=[A-Z])", "_", stem).lower()
        cand = None
        for prefix in ("fragment_", "activity_", "dialog_", ""):
            if prefix + snake in ids:
                cand = prefix + snake
                break
        if cand is None:
            warns.append(("viewBinding", f"{cls}: cannot map {btype} to a layout"))
            continue
        # only THIS file's own binding fields count: `m.binding.bottomNav`
        # belongs to MainActivity and must not be validated against a fragment
        # layout (negative lookbehind rejects a preceding dot/identifier char).
        used = set(re.findall(
            r"(?<![.\w])_binding\?\.([A-Za-z0-9_]+)|(?<![.\w])binding\.([A-Za-z0-9_]+)", t))
        decl = {camel(i) for i in ids[cand]}
        for a, b in used:
            name = a or b
            if not name or name in ("root", "inflate"):
                continue
            if name not in decl:
                fail("viewBinding", f"{cls}: binding.{name} not in {cand}.xml")
    ok("viewBinding", f"{len(ids)} layouts checked")


# --------------------------------------------------- 3. manifest <-> classes
def check_manifest():
    manifest = read(os.path.join(MAIN, "AndroidManifest.xml"))
    classes = set()
    for f in kt_files():
        t = read(f)
        pkg = re.search(r"^package\s+([\w.]+)", t, re.M)
        for m in re.findall(r"^(?:@\w+\s+)?(?:open |abstract |internal |final )*class\s+([A-Za-z0-9_]+)",
                            t, re.M):
            classes.add(pkg.group(1) + "." + m if pkg else m)
    for m in re.findall(r'android:name="(\.?[A-Za-z0-9_.]+)"', manifest):
        if not m.startswith("."):
            continue
        fq = "com.arohi.ai" + m
        if fq not in classes:
            fail("manifest", f"declared component {m} has no matching Kotlin class")
    # FGS type vs permission pairing (API 34 requirement)
    types = re.search(r'foregroundServiceType="([^"]+)"', manifest)
    if types:
        for t in types.group(1).split("|"):
            perm = "android.permission.FOREGROUND_SERVICE_" + re.sub(r"(?<!^)(?=[A-Z])", "_", t).upper()
            if perm not in manifest:
                fail("manifest", f"{perm} missing for foregroundServiceType={t}")
    ok("manifest", f"{len(classes)} classes, components resolved")


# ----------------------------------------------------- 4. kotlin syntax sanity
def check_kotlin_balance():
    for f in kt_files():
        t = read(f)
        # strip strings/comments crudely for balance counting
        s = re.sub(r'"""(?:.|\n)*?"""', '""', t)
        s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
        s = re.sub(r"'(?:\\.|[^'\\\n])'", "''", s)
        s = re.sub(r"/\*(?:.|\n)*?\*/", "", s)
        s = re.sub(r"//[^\n]*", "", s)
        for op, cl, label in (("{", "}", "braces"), ("(", ")", "parens"), ("[", "]", "brackets")):
            if s.count(op) != s.count(cl):
                fail("kotlin", f"{os.path.basename(f)}: unbalanced {label} "
                               f"{s.count(op)} vs {s.count(cl)}")
    ok("kotlin", f"{len(kt_files())} files balanced")


# ---------------------------------------------- 5. API integration invariants
def check_api_integration():
    gem = read(os.path.join(JAVA, "com", "arohi", "ai", "brain", "GeminiClient.kt"))
    oai = read(os.path.join(JAVA, "com", "arohi", "ai", "brain", "OpenAIClient.kt"))
    brain = read(os.path.join(JAVA, "com", "arohi", "ai", "brain", "ArohiBrain.kt"))

    if "generativelanguage.googleapis.com" not in gem:
        fail("api", "GeminiClient: official endpoint missing")
    if "api.openai.com/v1" not in oai:
        fail("api", "OpenAIClient: official endpoint missing")
    if ":generateContent" not in gem:
        fail("api", "GeminiClient: generateContent action missing")
    if "chat/completions" not in oai:
        fail("api", "OpenAIClient: chat/completions missing")
    # secrets must never ride in a URL
    if re.search(r"url\([^)]*\?key=", gem):
        fail("security", "GeminiClient: API key appears in a request URL (?key=)")
    # provider isolation: each client must use only its own endpoint
    if "api.openai.com" in gem:
        fail("api", "GeminiClient: contains an OpenAI endpoint (cross-provider leak)")
    if "generativelanguage" in oai:
        fail("api", "OpenAIClient: contains a Gemini endpoint (cross-provider leak)")
    # headers
    if "x-goog-api-key" not in gem:
        warns.append(("api", "GeminiClient: key header not used"))
    if "Bearer " not in oai:
        fail("api", "OpenAIClient: Bearer auth missing")
    # reasoning-model parameter compatibility
    if "isReasoningModel" not in oai or "max_completion_tokens" not in oai:
        fail("api", "OpenAIClient: reasoning-model parameter handling missing")
    # router honesty + enable/disable
    for token in ("autoFallback", "configuredProviders", "geminiEnabled", "openaiEnabled"):
        if token not in brain:
            fail("api", f"ArohiBrain: {token} not wired")
    if "openaiVerified" not in oai:
        fail("honesty", "OpenAIClient: verified flag not tracked (fake Connected risk)")
    ok("api", "Gemini + OpenAI endpoints, auth, isolation and routing verified")


# ------------------------------------------------------------ 6. build metadata
# ------------------------------------------------ 7. voice pipeline invariants
def check_voice():
    voice = os.path.join(JAVA, "com", "arohi", "ai", "voice")
    needed = ["EchoGuard.kt", "CallControl.kt", "SttManager.kt", "TtsManager.kt",
              "WakeWordEngine.kt", "ConversationEngine.kt", "VadMeter.kt", "ToneTracker.kt"]
    for n in needed:
        if not os.path.exists(os.path.join(voice, n)):
            fail("voice", f"missing voice module {n}")
    if fails:
        return
    tts = read(os.path.join(voice, "TtsManager.kt"))
    wake = read(os.path.join(voice, "WakeWordEngine.kt"))
    conv = read(os.path.join(voice, "ConversationEngine.kt"))
    echo = read(os.path.join(voice, "EchoGuard.kt"))
    # the echo guard must be fed at BOTH ends of playback
    if "EchoGuard.onSpeechStarted" not in tts or "EchoGuard.onSpeechEnded" not in tts:
        fail("voice", "TtsManager does not drive EchoGuard (self-echo protection lost)")
    if "EchoGuard.acceptWake" not in wake:
        fail("voice", "WakeWordEngine bypasses EchoGuard (she can wake on her own voice)")
    # barge-in must be implemented by a monitor that listens DURING playback
    mon_path = os.path.join(voice, "BargeInMonitor.kt")
    if not os.path.exists(mon_path):
        fail("voice", "BargeInMonitor missing — barge-in cannot work while she speaks")
    else:
        mon = read(mon_path)
        if "EchoGuard.looksLikeEcho" not in mon:
            fail("voice", "BargeInMonitor does not filter her own voice (echo loop risk)")
        if "BargeInMonitor.start" not in tts or "BargeInMonitor.stop" not in tts:
            fail("voice", "TtsManager does not drive BargeInMonitor")
    # wake words from the spec must all be recognised
    ext = read(os.path.join(JAVA, "com", "arohi", "ai", "util", "Extensions.kt"))
    for w in ("আরোহী", "আরোহি", "arohi"):
        if w not in ext:
            fail("voice", f"wake word variant {w} not handled in Extensions")
    # TTS must still support both engines
    for token in ("elevenlabs.io/v1/text-to-speech", "TextToSpeech"):
        if token not in tts:
            fail("voice", f"TTS engine path missing: {token}")
    if "xi-api-key" not in tts:
        fail("security", "ElevenLabs request without xi-api-key header")
    ok("voice", "wake → STT → brain → TTS → barge-in → echo guard wired")


# ------------------------------------------------------- 8. CI / repo hygiene
def check_repo():
    wf_dir = os.path.join(ROOT, ".github", "workflows")
    if not os.path.isdir(wf_dir):
        fail("ci", "no .github/workflows — APK cannot be built anywhere but a dev machine")
    else:
        files = [f for f in glob.glob(os.path.join(wf_dir, "*.yml"))]
        if not files:
            fail("ci", "workflows directory is empty")
        joined = "".join(read(f) for f in files)
        for token in ("setup-java", "assembleDebug", "upload-artifact"):
            if token not in joined:
                fail("ci", f"workflow missing step: {token}")
        # GitHub rejects the ENTIRE workflow (0 jobs, instant failure) if the
        # `secrets` context appears in a step-level `if:`. Catch it here instead
        # of discovering it after a push.
        for f in files:
            for line in read(f).splitlines():
                s = line.strip()
                if s.startswith("if:") and "secrets." in s:
                    fail("ci", f"{os.path.basename(f)}: secrets context in a step `if:` "
                               f"-> GitHub rejects the workflow ({s})")
    if not os.path.exists(os.path.join(ROOT, ".gitignore")):
        warn_hygiene("missing .gitignore (build output could be committed)")
    gi = read(os.path.join(ROOT, ".gitignore")) if os.path.exists(os.path.join(ROOT, ".gitignore")) else ""
    for entry in ("build/", ".gradle/", "*.jks"):
        if entry not in gi:
            warn_hygiene(f".gitignore does not exclude {entry}")
    if not os.path.exists(os.path.join(ROOT, "backup")) or \
            not glob.glob(os.path.join(ROOT, "backup", "*.zip")):
        warn_hygiene("no pristine source backup in backup/ (rollback risk)")
    ok("ci", f"{len(glob.glob(os.path.join(wf_dir, '*.yml')))} workflow(s) build + publish the APK")


def warn_hygiene(msg):
    warns.append(("repo", msg))


def check_gradle():
    g = read(os.path.join(ROOT, "app", "build.gradle"))
    vc = re.search(r"versionCode\s+(\d+)", g)
    vn = re.search(r'versionName\s+"([^"]+)"', g)
    mins = re.search(r"minSdkVersion\s+(\d+)|minSdk\s+(\d+)", g)
    if not vc or not vn:
        fail("gradle", "versionCode/versionName missing")
    else:
        ok("gradle", f"versionCode {vc.group(1)}, versionName {vn.group(1)}, "
                     f"minSdk {(mins.group(1) or mins.group(2)) if mins else '?'}")
    # Machine-local paths must never be committed — the v6 archive shipped a
    # hardcoded org.gradle.java.home that broke every other machine and CI.
    gp = os.path.join(ROOT, "gradle.properties")
    if os.path.exists(gp):
        active = [l for l in read(gp).splitlines() if l.strip().startswith("org.gradle.java.home")]
        if active:
            fail("gradle", "gradle.properties pins org.gradle.java.home — build only works on one machine")
    for dep in ("okhttp", "room-runtime", "security-crypto", "gson"):
        if dep not in g:
            fail("gradle", f"dependency {dep} missing")


def main():
    check_resources()
    check_view_binding()
    check_manifest()
    check_kotlin_balance()
    check_api_integration()
    check_voice()
    check_repo()
    check_gradle()

    print("=" * 68)
    print("AROHI STATIC VERIFICATION")
    print("=" * 68)
    for check, msg in oks:
        print(f"  PASS  {check:<12} {msg}")
    for check, msg in warns:
        print(f"  WARN  {check:<12} {msg}")
    for check, msg in fails:
        print(f"  FAIL  {check:<12} {msg}")
    print("-" * 68)
    print(f"{len(oks)} passed, {len(warns)} warnings, {len(fails)} failed")
    print("NOTE: static analysis only — it does not replace `./gradlew assembleDebug`.")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
