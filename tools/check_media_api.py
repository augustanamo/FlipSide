"""
核查本工程用到的 media3 API 到底要不要 opt-in。

为什么需要它：media3 用 @UnstableApi 区分稳定/不稳定 API，而它的约束层级很容易判断错。
实测（javap 看注解类自身的元注解）：`UnstableApi` 带的是
**androidx.annotation.RequiresOptIn**，**不是 kotlin.RequiresOptIn**。所以：

  - **Kotlin 编译器不拦**。不写任何 opt-in，`assembleDebug` 照样成功 ——
    本工程 0.1.0 / 0.2.0 早期就是这么编过的。拦它的是 **lint 的
    UnsafeOptInUsageError**（release 的 lintVital 也会报）。这是个**静态检查层**约束。
  - 反过来说，**把 `@UnstableApi` 标在自己的函数上是错的**：那是把看门狗标记
    贴到自己身上，等于声明"我这个函数也不稳定"，调用方会被一起要求 opt-in，
    把问题扩散到整条调用链。局部接受只能用 `@androidx.annotation.OptIn(...)`。

另外「凭记忆判断版本」也不靠谱：PlayerView 的 setShutterBackgroundColor 到底要不要 opt-in，
不同版本不一样。所以一律实测。

判定方法：javap -v 把成员级注解打印在**该成员块的末尾**（`Code:` / `LineNumberTable`
之后），所以必须扫完整块 —— 早先这里写的是"只看 Code: 之前"，那是**假阴性**的来源：
抽象方法（没有 Code:）能查出来，而带实现的方法（PlayerView.setPlayer 这类）会被漏掉，
一律报 stable。现在改成整块扫描。

单纯 grep 上下文若干行则是另一个方向的错：会把下一个成员的注解算进来（假阳性）。
成员块的分界要用「两格缩进 + 修饰符」的成员头来切，这正是下面 blocks 的切法。
"""
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

# 工程根 = 本脚本所在目录的上一级。用相对推导而不是写死绝对路径，
# 换台机器 / 别人 clone 下来也能直接跑。
ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLE_CACHE = pathlib.Path.home() / ".gradle/caches/modules-2/files-2.1"


def _find_javap() -> pathlib.Path:
    """按 JAVA_HOME → PATH → 常见 JDK 安装位置 的顺序找 javap。

    以前是写死本机 temurin-21 的绝对路径，别人 clone 下来第一句就报
    「找不到 javap」。JDK 的位置属于环境，不该固化在脚本里。
    """
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = pathlib.Path(java_home) / "bin/javap"
        if candidate.exists():
            return candidate
    found = shutil.which("javap")
    if found:
        return pathlib.Path(found)
    for base in (
        pathlib.Path.home() / "Library/Java/JavaVirtualMachines",
        pathlib.Path("/Library/Java/JavaVirtualMachines"),
        pathlib.Path("/usr/lib/jvm"),
    ):
        # macOS 的 JDK 多一层 Contents/Home，Linux 没有
        for pattern in ("*/Contents/Home/bin/javap", "*/bin/javap"):
            hit = sorted(base.glob(pattern))
            if hit:
                return hit[0]
    return pathlib.Path("javap")


JAVAP = _find_javap()

UNSTABLE_MARKER = "androidx.media3.common.util.UnstableApi"

# 局部接受时该写的注解（androidx 那套，不是 kotlin.OptIn）
OPT_IN_MARK = "@androidx.annotation.OptIn"

# 「知道它不稳定、并且已经在源码里局部接受」的成员：签名 -> 必须出现 opt-in 的源文件（相对
# app/src/main/java/com/magfold/cast）。**只有那个文件里真的写了 OptIn 才算数**，
# 否则仍然按未处理报出来 —— 免得这份清单变成"说了就过"的免罪牌。
ACCEPTED = {
    "public void setResizeMode(int)": "ui/VideoStage.kt",
    "public void setShutterBackgroundColor(int)": "ui/VideoStage.kt",
    "public void setKeepContentOnPlayerReset(boolean)": "ui/VideoStage.kt",
}

# 本工程实际调用到的 media3 API：(类, 成员签名片段, 说明)
API_USED = [
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void release()", "释放播放器"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void setRepeatMode(int)", "循环模式"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void setVolume(float)", "静音"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void setPlayWhenReady(boolean)", "起停"),
    ("androidx.media3.exoplayer.ExoPlayer",
     "public abstract void setMediaItem(androidx.media3.common.MediaItem)", "换片"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void prepare()", "预备"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void stop()", "停止"),
    ("androidx.media3.exoplayer.ExoPlayer", "public abstract void clearMediaItems()", "清队列"),
    ("androidx.media3.common.Player",
     "public abstract void addListener(androidx.media3.common.Player$Listener)", "挂状态监听"),
    ("androidx.media3.common.Player", "public static final int REPEAT_MODE_ONE", "单件循环常量"),
    ("androidx.media3.common.Player", "public static final int REPEAT_MODE_OFF", "不循环常量"),
    # 轮播靠它判断"视频放完了"。注意带 @UnstableApi 的是已废弃的
    # onPlayerStateChanged(boolean,int)，不是这个。
    ("androidx.media3.common.Player$Listener",
     "public default void onPlaybackStateChanged(int)", "播放结束回调"),
    # 播不出来时留痕。外屏是纯展示、一个字的提示都没有，解码失败的结果只是
    # 一帧静止画面，没有这条日志就分不清"没在播"和"解不了码"。
    ("androidx.media3.common.Player$Listener",
     "public default void onPlayerError(androidx.media3.common.PlaybackException)", "播放失败留痕"),
    ("androidx.media3.exoplayer.ExoPlayer$Builder",
     "public androidx.media3.exoplayer.ExoPlayer build()", "建实例"),
    ("androidx.media3.exoplayer.ExoPlayer$Builder",
     "public androidx.media3.exoplayer.ExoPlayer$Builder(android.content.Context)", "构造"),
    ("androidx.media3.common.MediaItem", "public static androidx.media3.common.MediaItem fromUri",
     "按 uri 建媒体项"),
    ("androidx.media3.ui.PlayerView", "public androidx.media3.ui.PlayerView(android.content.Context)",
     "视频视图"),
    ("androidx.media3.ui.PlayerView", "public void setPlayer(androidx.media3.common.Player)", "挂播放器"),
    ("androidx.media3.ui.PlayerView", "public void setResizeMode(int)", "填满/适应"),
    ("androidx.media3.ui.PlayerView", "public void setUseController(boolean)", "关自带控件"),
    ("androidx.media3.ui.PlayerView", "public void setShutterBackgroundColor(int)", "遮罩设透明"),
    ("androidx.media3.ui.PlayerView", "public void setKeepContentOnPlayerReset(boolean)", "保留末帧"),
    ("androidx.media3.ui.AspectRatioFrameLayout", "public static final int RESIZE_MODE_ZOOM",
     "填满常量"),
    ("androidx.media3.ui.AspectRatioFrameLayout", "public static final int RESIZE_MODE_FIT",
     "适应常量"),
]


def media3_version() -> str:
    toml = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    match = re.search(r'^media3\s*=\s*"([^"]+)"', toml, re.M)
    if not match:
        raise SystemExit("libs.versions.toml 里找不到 media3 版本")
    return match.group(1)


def artifact_of(cls: str) -> str:
    """androidx.media3.ui.PlayerView -> media3-ui"""
    parts = cls.split(".")
    return f"media3-{parts[2]}" if len(parts) > 2 and parts[1] == "media3" else "media3-common"


def opted_in(relative: str) -> bool:
    """那个源文件里是否真的写了 androidx 的 OptIn 注解。"""
    path = ROOT / "app/src/main/java/com/magfold/cast" / relative
    return path.exists() and OPT_IN_MARK in path.read_text(encoding="utf-8")


def fetch_aar(artifact: str, version: str, workdir: pathlib.Path):
    """Gradle 缓存里没有就去阿里云镜像拉一份。

    场景：还没构建过（或没依赖 media3-ui）时，也要能把 API 稳定性查完 ——
    这正是本工具存在的意义，不能反过来要求先构建。
    """
    url = (
        "https://maven.aliyun.com/repository/google/androidx/media3/"
        f"{artifact}/{version}/{artifact}-{version}.aar"
    )
    target = workdir / f"{artifact}-{version}.aar"
    try:
        import urllib.request

        print(f"  （缓存里没有 {artifact}，从镜像取）")
        with urllib.request.urlopen(url, timeout=60) as response:
            target.write_bytes(response.read())
        return target
    except Exception as exc:  # 网络不可用时不该让整个检查失败
        print(f"  ! 取不到 {artifact}：{exc}")
        return None


def unpack_aars(version: str, workdir: pathlib.Path) -> list:
    """把用到的 media3 aar 里的 classes 解到同一个目录，作为 javap 的 classpath。"""
    classpath = workdir / "cp"
    classpath.mkdir(parents=True, exist_ok=True)

    aars = list(GRADLE_CACHE.glob(f"androidx.media3/*/{version}/*/*.aar"))
    have = {path.stem.replace(f"-{version}", "") for path in aars}

    # 逐个补上「源码里用到但缓存里还没有」的模块
    for artifact in sorted({artifact_of(cls) for cls, _, _ in API_USED}):
        if artifact in have:
            continue
        fetched = fetch_aar(artifact, version, workdir)
        if fetched:
            aars.append(fetched)

    if not aars:
        raise SystemExit(
            f"拿不到任何 media3 {version} 的 aar：Gradle 缓存是空的，镜像也连不上。"
        )

    found = []
    for aar in aars:
        with zipfile.ZipFile(aar) as outer:
            if "classes.jar" not in outer.namelist():
                continue
            jar_bytes = outer.read("classes.jar")
        jar_path = workdir / f"{aar.stem}.jar"
        jar_path.write_bytes(jar_bytes)
        with zipfile.ZipFile(jar_path) as jar:
            jar.extractall(classpath)
        found.append(aar.stem)
    return sorted(set(found))


def unstable_members(classpath: pathlib.Path, cls: str) -> set:
    """返回该类里带 @UnstableApi 的成员头行。"""
    result = subprocess.run(
        [str(JAVAP), "-v", "-cp", str(classpath), cls],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"javap 读不出 {cls}：{result.stderr.strip()}")

    blocks, current = [], None
    for line in result.stdout.splitlines():
        # 成员头：两格缩进 + 修饰符，且以 '(' 或 ';' 收尾（三格以上缩进的是描述符等）
        is_header = bool(
            re.match(r"^  (public|protected|private|static|final|abstract|native)\b", line)
        ) and ("(" in line or line.rstrip().endswith(";"))
        if is_header:
            if current is not None:
                blocks.append(current)
            current = [line.strip()]
        elif current is not None:
            current.append(line.strip())
    if current is not None:
        blocks.append(current)

    unstable = set()
    for block in blocks:
        head = block[0]
        # 整块扫描：成员级注解在 javap 里出现在块**末尾**（Code: 之后），
        # 只扫 Code: 之前会漏掉所有带实现的方法。
        if UNSTABLE_MARKER in "\n".join(block[1:]):
            unstable.add(head)
    return unstable


def main() -> int:
    if not JAVAP.exists():
        raise SystemExit(f"找不到 javap：{JAVAP}")
    version = media3_version()
    workdir = pathlib.Path(tempfile.mkdtemp(prefix="cast-media3-"))
    try:
        artifacts = unpack_aars(version, workdir)
        classpath = workdir / "cp"
        print(f"media3 {version}，已解包：{', '.join(artifacts)}")

        cache = {}
        problems = []
        accepted = []
        for cls, signature, purpose in API_USED:
            if cls not in cache:
                cache[cls] = unstable_members(classpath, cls)
            head = signature
            short = f"{cls.split('.')[-1]}.{signature.split()[-1]}"
            if not any(head.startswith(item) or item.startswith(head) for item in cache[cls]):
                print(f"  ✓ stable    {short}  ({purpose})")
                continue

            # 确实带 @UnstableApi。看它是不是我们「知道并已局部接受」的那几个 ——
            # 接受的前提是那个源文件里真的写了 androidx 的 OptIn，否则不算数。
            accepted_in = ACCEPTED.get(signature)
            if accepted_in and opted_in(accepted_in):
                accepted.append(short)
                print(f"  ~ 已接受    {short}  ({purpose}，{accepted_in} 里标了 {OPT_IN_MARK})")
                continue

            problems.append(f"{cls} :: {signature}  —— {purpose}")
            print(f"  ✗ UNSTABLE  {short}  ({purpose})")

        print()
        if problems:
            print("下面这些成员带 @UnstableApi（lint 会报 UnsafeOptInUsageError）：")
            for item in problems:
                print("  -", item)
            print()
            print("处理办法二选一：")
            print("  1) 换成稳定替代；")
            print("  2) 在**用到它的那一个函数**上标")
            print("     @androidx.annotation.OptIn(markerClass = [UnstableApi::class])")
            print("     —— 不要标 @UnstableApi：那会把要求传染给所有调用方。")
            print("     注意 Kotlin 编译器不会因此失败（这是 lint 层约束），别因为'能编过'就不管。")
            return 1

        if accepted:
            print(
                f"其中 {len(accepted)} 个成员带 @UnstableApi，已在源码里用 "
                f"{OPT_IN_MARK} 局部接受（上面的 ~ 行）；其余全是稳定 API。"
            )
        else:
            print("全部是稳定 API，源码里不需要任何 @OptIn / @UnstableApi 声明。")
        return 0
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
