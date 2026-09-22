"""
MagFoldCast 静态校验：没有 JDK / Android SDK 的前提下，把能查的先查掉。

三类问题：
1) 括号配对（剥掉注释与字符串之后再数）
2) **必需 import 有没有漏**。Compose 最常见的编译失败就是漏一行 import，
   而报错信息（"Unresolved reference: fillMaxSize"）看着像函数不存在，很误导。
   这里按「名字 -> 必须有一条以 .名字 结尾的 import」来查。
3) 大驼峰调用必须有对应声明（漏写 composable 时能提前发现）。
"""
import pathlib
import re
import sys

ROOT = pathlib.Path("/Users/augustanamo/WorkBuddy/杂志/MagFoldCast")

# 名字 -> 出现该名字就必须存在对应 import。
# 只放「几乎只可能是 Compose/AndroidX 提供的」符号，自己的类不在此列。
REQUIRED_IMPORTS = {
    "Text": r"\bText\s*\(",
    "Image": r"\bImage\s*\(",
    "Box": r"\bBox\s*\(",
    "Column": r"\bColumn\s*\(",
    "Row": r"\bRow\s*\(",
    "Spacer": r"\bSpacer\s*\(",
    "BoxWithConstraints": r"\bBoxWithConstraints\s*\(",
    "LazyVerticalGrid": r"\bLazyVerticalGrid\s*\(",
    "GridCells": r"\bGridCells\b",
    "itemsIndexed": r"\bitemsIndexed\s*\(",
    "Composable": r"@Composable",
    "Modifier": r"\bModifier\b",
    "Alignment": r"\bAlignment\b",
    "ContentScale": r"\bContentScale\b",
    "TextOverflow": r"\bTextOverflow\b",
    "TextAlign": r"\bTextAlign\b",
    "FontWeight": r"\bFontWeight\b",
    "RoundedCornerShape": r"\bRoundedCornerShape\b",
    "CircleShape": r"\bCircleShape\b",
    "Brush": r"\bBrush\b",
    "Color": r"\bColor\s*\(",
    "dp": r"[^\w.]dp\b",
    "sp": r"[^\w.]sp\b",
    "em": r"[^\w.]em\b",
    "collectAsState": r"\.collectAsState\s*\(",
    # by 委托里只有 remember()/collectAsState() 这几种才需要 getValue；
    # by lazy 不需要，所以不能简单用 \bby\s+ 去卡。
    "getValue": r"\bby\s+(?:remember\b|[\w.]+\.collectAsState\b)",
    "setValue": r"\bvar\s+\w+\s+by\s+",
    "remember": r"\bremember\s*\(",
    "mutableStateOf": r"\bmutableStateOf\s*\(",
    "LaunchedEffect": r"\bLaunchedEffect\s*\(",
    "BackHandler": r"\bBackHandler\s*\(",
    "AnimatedVisibility": r"\bAnimatedVisibility\s*\(",
    "fadeIn": r"\bfadeIn\s*\(",
    "fadeOut": r"\bfadeOut\s*\(",
    "slideInVertically": r"\bslideInVertically\s*\(",
    "slideOutVertically": r"\bslideOutVertically\s*\(",
    "pointerInput": r"\.pointerInput\s*\(",
    "detectHorizontalDragGestures": r"\bdetectHorizontalDragGestures\s*\(",
    "MutableInteractionSource": r"\bMutableInteractionSource\b",
    "drawBehind": r"\.drawBehind\s*\{",
    "Offset": r"\bOffset\s*\(",
    "LocalDensity": r"\bLocalDensity\b",
    "rememberScrollState": r"\brememberScrollState\s*\(",
    "verticalScroll": r"\.verticalScroll\s*\(",
    "RowScope": r"\bRowScope\b",
    "SystemClock": r"\bSystemClock\.\w+",
    "Job": r"\bJob\b",
    "delay": r"\bdelay\s*\(",
    "WindowInsets": r"\bWindowInsets\b",
    "safeDrawing": r"\bWindowInsets\.safeDrawing\b",
    "windowInsetsPadding": r"\.windowInsetsPadding\s*\(",
    "asImageBitmap": r"\.asImageBitmap\s*\(",
    "LocalContext": r"\bLocalContext\b",
    "clip": r"\.clip\s*\(",
    "shadow": r"\.shadow\s*\(",
    "border": r"\.border\s*\(",
    "background": r"\.background\s*\(",
    "clickable": r"\.clickable\s*\(",
    "aspectRatio": r"\.aspectRatio\s*\(",
    "fillMaxSize": r"\.fillMaxSize\s*\(",
    "fillMaxWidth": r"\.fillMaxWidth\s*\(",
    "fillMaxHeight": r"\.fillMaxHeight\s*\(",
    "padding": r"\.padding\s*\(",
    "size": r"\.size\s*\(",
    # width/height 必须带"接收者"判断：`bounds.width()` 是 android.graphics.Rect
    # 的方法，跟 Compose 的 `Modifier.width()` 同名。真实用法前面一定是
    # `Modifier` 或前一个修饰符的 `)`（链式），所以只认这两种前缀。
    "height": r"(?:Modifier|\))\.height\s*\(",
    "width": r"(?:Modifier|\))\.width\s*\(",
    # 下面这些**不需要** import，别再列进来当必需项：
    #   weight            —— RowScope / ColumnScope 的成员扩展
    #   Arrangement.spacedBy —— Arrangement 的伴生方法
    #   verticalAlignment / horizontalAlignment / contentAlignment —— 参数名
    "PaddingValues": r"\bPaddingValues\b",
    "Arrangement": r"\bArrangement\b",
    "AndroidView": r"\bAndroidView\s*\(",
    "ExoPlayer": r"\bExoPlayer\b",
    "PlayerView": r"\bPlayerView\b",
    "MediaItem": r"\bMediaItem\b",
    "Player": r"\bPlayer\.\w+",
    "UnstableApi": r"@UnstableApi",
    "Intent": r"\bIntent\.\w+",
    "Uri": r"\bUri\b",
    "Bitmap": r"\bBitmap\b",
    "LruCache": r"\bLruCache\b",
    "ImageDecoder": r"\bImageDecoder\b",
    "Size": r"\bSize\s*\(",
    "OpenableColumns": r"\bOpenableColumns\b",
    "Dispatchers": r"\bDispatchers\b",
    "withContext": r"\bwithContext\s*\(",
    "CoroutineScope": r"\bCoroutineScope\b",
    "StateFlow": r"\bStateFlow\b",
    "MutableStateFlow": r"\bMutableStateFlow\b",
    "asStateFlow": r"\.asStateFlow\s*\(",
    "Lifecycle": r"\bLifecycle\b",
    "LifecycleOwner": r"\bLifecycleOwner\b",
    "repeatOnLifecycle": r"\brepeatOnLifecycle\b",
    "RequiresApi": r"@RequiresApi",
    "WindowAreaController": r"\bWindowAreaController\b",
    "WindowAreaCapability": r"\bWindowAreaCapability\b",
    "WindowAreaInfo": r"\bWindowAreaInfo\b",
    "ComponentActivity": r"\bComponentActivity\b",
    "enableEdgeToEdge": r"\benableEdgeToEdge\s*\(",
    "setContent": r"\bsetContent\s*\{",
    "ActivityResultContracts": r"\bActivityResultContracts\b",
    "PickVisualMediaRequest": r"\bPickVisualMediaRequest\s*\(",
    "Log": r"\bLog\.\w+",
}

# 第三方 / 平台符号，用于第 3 步排除误报
PLATFORM_SYMBOLS = set(REQUIRED_IMPORTS) | {
    "Box", "Column", "Row", "Surface", "Canvas", "Icon", "Button", "Card",
    "Slider", "Divider", "MaterialTheme", "Dp", "Offset", "IntOffset", "IntSize",
    "TransformOrigin", "FilterQuality", "GraphicsLayerScope", "DrawScope",
    "ImageBitmap", "Path", "StrokeCap", "ArrayList", "LinkedHashSet", "HashMap",
    "Pair", "Triple", "StringBuilder", "Regex", "Exception", "Throwable", "Result",
    "Mutex", "Job", "SupervisorJob", "AbstractComposeView", "LifecycleRegistry",
    "ViewModelStore", "ViewModelStoreOwner", "SavedStateRegistry",
    "SavedStateRegistryController", "SavedStateRegistryOwner", "SurfaceHolder",
    "SurfaceView", "TextureView", "Runtime", "System", "Build", "Thread", "Math",
    "T", "R", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap",
    "Array", "Suppress", "OptIn", "JvmStatic", "JvmField", "Volatile", "Stable",
    "Synchronized", "Preview", "AspectRatioFrameLayout", "ContextCompat", "Color",
    "CoroutineScope", "SupervisorJob", "Log", "Uri", "Size", "File", "Files",
}

# 被 import 的行尾名字（也允许 `import a.b.C as D`）
IMPORT_NAME = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.M)


def strip_kotlin(src: str) -> str:
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                raise ValueError("未闭合的原始字符串")
            out.append('"RAW"')
            i = j + 3
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append('"S"')
            i = j + 1
        elif src[i] == "'":
            j = i + 1
            if j < n and src[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and src[j] == "'":
                out.append("'C'")
                i = j + 1
            else:
                out.append("'")
                i += 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def balance(text: str, label: str) -> list:
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for idx, ch in enumerate(text):
        if ch in "([{":
            stack.append((ch, idx))
        elif ch in ")]}":
            if not stack:
                return [f"{label}: 多余的 '{ch}'"]
            top, pos = stack.pop()
            if top != pairs[ch]:
                line = text.count("\n", 0, idx) + 1
                return [f"{label}: 第 {line} 行 '{ch}' 与第 {pos} 处的 '{top}' 不匹配"]
    if stack:
        top, pos = stack[-1]
        line = text.count("\n", 0, pos) + 1
        return [f"{label}: 第 {line} 行 '{top}' 没有闭合"]
    return []


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def check_imports(path, stripped) -> list:
    """出现符号但缺少对应 import。"""
    imported = set()
    raw = path.read_text(encoding="utf-8")
    for match in IMPORT_NAME.finditer(raw):
        fq = match.group(1)
        alias = match.group(2)
        imported.add(alias if alias else fq.rsplit(".", 1)[-1])
        if fq.endswith(".*"):
            imported.add("*")

    problems = []
    for name, pattern in REQUIRED_IMPORTS.items():
        if name in imported or "*" in imported:
            continue
        hit = re.search(pattern, stripped)
        if hit:
            problems.append(
                f"{path.relative_to(ROOT)}: 第 {line_of(stripped, hit.start())} 行"
                f"用了 {name}，但没有 import"
            )
    return problems


def check_own_symbols(files, stripped_sources) -> list:
    declared = set()
    for path in files:
        raw = path.read_text(encoding="utf-8")
        declared |= set(re.findall(r"\bfun\s+(?:<[^>]*>\s*)?(\w+)", raw))
        declared |= set(re.findall(r"\b(?:class|interface|object)\s+(\w+)", raw))
        declared |= set(re.findall(r"\b(?:val|var)\s+(\w+)", raw))
        declared |= set(re.findall(r"^\s*import\s+[\w.]*?(\w+)\s*$", raw, re.M))

    problems = []
    for path, src in sorted(stripped_sources.items()):
        seen = set()
        for match in re.finditer(r"(?<![\w.])([A-Z]\w*)\s*\(", src):
            name = match.group(1)
            if name in declared or name in PLATFORM_SYMBOLS or name in seen:
                continue
            seen.add(name)
            problems.append(
                f"{path.relative_to(ROOT)}: 第 {line_of(src, match.start())} 行"
                f"调用了未声明的符号 {name}(...)"
            )
    return problems


def main() -> int:
    files = sorted(
        p for p in ROOT.rglob("*.kt") if "build" not in p.relative_to(ROOT).parts
    )
    if not files:
        print("没有找到 .kt 文件")
        return 1

    problems = []
    stripped_sources = {}
    total = 0
    for path in files:
        raw = path.read_text(encoding="utf-8")
        total += raw.count("\n") + 1
        try:
            stripped = strip_kotlin(raw)
        except ValueError as exc:
            problems.append(f"{path.name}: {exc}")
            continue
        stripped_sources[path] = stripped
        problems += balance(stripped, str(path.relative_to(ROOT)))
        problems += check_imports(path, stripped)

    problems += check_own_symbols(files, stripped_sources)

    # 常量检查：包名与目录是否一致外，顺手确认命名空间没写错
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    namespace = re.search(r'namespace\s*=\s*"([^"]+)"', gradle)
    if not namespace:
        problems.append("app/build.gradle.kts 里找不到 namespace")
    else:
        expected = namespace.group(1).replace(".", "/")
        for path in files:
            if expected not in str(path):
                problems.append(
                    f"{path.relative_to(ROOT)}: 不在 namespace {namespace.group(1)} 的目录下"
                )

    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    for activity in re.findall(r'android:name="\.(\w+)"', manifest):
        if not (ROOT / f"app/src/main/java/{namespace.group(1).replace('.', '/')}/{activity}.kt").exists():
            problems.append(f"Manifest 声明的 {activity} 没有对应源文件")

    print(f"Kotlin 文件 {len(files)} 个，共 {total} 行")

    if problems:
        print("\n发现问题：")
        for item in problems:
            print("  ✗", item)
        return 1
    print("\n静态校验通过：括号配对 / import 完整 / 符号已声明")
    return 0


if __name__ == "__main__":
    sys.exit(main())
