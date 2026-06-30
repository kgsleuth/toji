package io.toji.infrastructure
import io.toji.cli._

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.sys.process.*
import scala.util.Try

/** Installs or updates a tool binary from its GitHub releases.
  *
  * Supports public + private repos (via gh auth or PAT).
  * Tracks per-tool last-checked state so we don't hammer GitHub.
  * Uses epoch millis for time to be Scala Native friendly.
  */
object Installer:

  private val DefaultIntervalDays = 7
  private val MillisPerDay = 24L * 60 * 60 * 1000

  case class InstallResult(tool: String, version: String, dest: Path, wasUpdate: Boolean)

  def install(
    nameOrRepo: String,
    version: String = "latest",
    force: Boolean = false,
    destDirOverride: Option[Path] = None,
    ignoreInterval: Boolean = false
  ): InstallResult =
    val repo = ToolRegistry.defaultRepoFor(nameOrRepo)
    val base = ToolRegistry.baseFor(nameOrRepo)
    val asset = Platform.detectAsset(base)
    val tag = normalizeVersion(version)

    // Throttle only background/cron-style checks. Explicit `toji update` passes ignoreInterval=true.
    if version == "latest" && !force && !ignoreInterval then
      readState(base) match
        case Some(st) if !isDue(st.lastCheckedAtMillis, System.currentTimeMillis(), intervalDaysFromEnv()) =>
          val next = st.lastCheckedAtMillis + intervalDaysFromEnv().toLong * MillisPerDay
          info(s"$base: checked ${formatAge(st.lastCheckedAtMillis)} ago; next auto-check ${formatWhen(next)} (use --force)")
          val dest = resolveDest(base, destDirOverride)
          return InstallResult(base, st.version, dest, wasUpdate = false)
        case _ => ()

    info(s"repo $repo")
    info(s"platform ${asset.value}")
    info(s"version $tag")

    val (release, assetMeta) = GitHubRelease.resolve(repo, tag, asset.value)
    val dest = resolveDest(base, destDirOverride)

    Files.createDirectories(dest.getParent)
    val tmp = Files.createTempFile(s"toji-install-$base-", ".tmp")
    try
      GitHubRelease.download(repo, release, assetMeta, tmp)
      chmodExecutable(tmp)
      val newVer = smokeVersion(tmp, base)
      val checked = System.currentTimeMillis()
      val stateVer = readState(base).map(_.version).getOrElse("")
      val installedVer = if Files.isRegularFile(dest) then Some(smokeVersion(dest, base)) else None
      val alreadyCurrent =
        sameVersion(newVer, stateVer) ||
        installedVer.exists(v => sameVersion(newVer, v))

      if alreadyCurrent && !force then
        writeState(base, checked, newVer)
        info(s"$base already up to date ($newVer)")
        InstallResult(base, newVer, dest, wasUpdate = false)
      else
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        writeState(base, checked, newVer)
        info(s"installed $base $newVer -> $dest")
        InstallResult(base, newVer, dest, wasUpdate = true)
    finally
      Files.deleteIfExists(tmp)

  def selfUpdate(force: Boolean = false, ignoreInterval: Boolean = false): InstallResult =
    install("toji", "latest", force, ignoreInterval = ignoreInterval)

  // --- state per tool (millis) ---

  private case class ToolState(lastCheckedAtMillis: Long, version: String)

  private def stateDir(): Path =
    val base =
      Option(System.getenv("TOJI_STATE_DIR")).filter(_.nonEmpty)
        .orElse(Option(System.getenv("XDG_STATE_HOME")).filter(_.nonEmpty))
        .getOrElse(Paths.get(System.getProperty("user.home"), ".local", "state").toString)
    Paths.get(base, "toji")

  private def statePath(toolBase: String): Path =
    stateDir().resolve(s"$toolBase.json")

  private def readState(toolBase: String): Option[ToolState] =
    val p = statePath(toolBase)
    if !Files.isRegularFile(p) then None
    else
      Try {
        val j = ujson.read(Files.readString(p))
        val millis =
          j.obj.get("last_checked_millis") match
            case Some(v) if v.value.isInstanceOf[java.lang.Number] => v.num.toLong
            case Some(v) => v.str.trim.toLongOption.getOrElse(System.currentTimeMillis())
            case None    => System.currentTimeMillis()
        ToolState(millis, j("version").str)
      }.toOption

  private def writeState(toolBase: String, millis: Long, ver: String): Unit =
    val p = statePath(toolBase)
    Files.createDirectories(p.getParent)
    val j = ujson.Obj(
      "last_checked_millis" -> millis,
      "version" -> ver
    )
    Files.writeString(p, ujson.write(j, indent = 2))

  private def intervalDaysFromEnv(): Int =
    Option(System.getenv("TOJI_UPDATE_INTERVAL_DAYS"))
      .flatMap(_.trim.toIntOption).filter(_ > 0).getOrElse(DefaultIntervalDays)

  private def isDue(lastMillis: Long, nowMillis: Long, days: Int): Boolean =
    nowMillis >= lastMillis + days.toLong * MillisPerDay

  // --- helpers ---

  private def normalizeVersion(v: String): String =
    v match
      case "latest" => "latest"
      case x if x.startsWith("v") => x
      case x => s"v$x"

  private def resolveDest(base: String, overrideDir: Option[Path]): Path =
    overrideDir match
      case Some(d) => d.resolve(base)
      case None =>
        val dir = Option(System.getenv("TOJI_INSTALL_DIR")).filter(_.nonEmpty)
          .getOrElse(Paths.get(System.getProperty("user.home"), ".local", "bin").toString)
        Paths.get(dir, base)

  private def chmodExecutable(p: Path): Unit =
    if Process(Seq("chmod", "+x", p.toString)).! != 0 then
      throw ValidationError(s"chmod +x failed for $p")

  private def smokeVersion(p: Path, expectedBase: String): String =
    Try(Process(Seq(p.toString, "--version")).!!).toOption
      .orElse(Try(Process(Seq(p.toString, "-V")).!!).toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse {
        Try(Process(Seq(p.toString, "--help")).!!.linesIterator.next().trim).getOrElse(s"$expectedBase (unknown version)")
      }

  private def sameVersion(a: String, b: String): Boolean =
    normalizeVersionLabel(a) == normalizeVersionLabel(b)

  private def normalizeVersionLabel(raw: String): String =
    raw.stripPrefix("toji ").stripPrefix("curtain ").stripPrefix("shikigami ").stripPrefix("yuta ").stripPrefix("kenjaku ").stripPrefix("dhruv ").takeWhile(c => c != ' ' && c != '(').trim.toLowerCase

  private def info(msg: String): Unit = println(s"toji: $msg")

  private def formatAge(millis: Long): String =
    val d = (System.currentTimeMillis() - millis) / MillisPerDay
    if d <= 0 then "today" else if d == 1 then "1 day" else s"$d days"

  private def formatWhen(futureMillis: Long): String =
    val d = (futureMillis - System.currentTimeMillis()) / MillisPerDay
    if d <= 0 then "now" else if d == 1 then "in 1 day" else s"in $d days"

  // Tool base names tracked under TOJI_STATE_DIR (one .json per tool; excludes registry).
  def installedTools(): List[String] =
    val dir = stateDir()
    if !Files.isDirectory(dir) then Nil
    else
      import scala.jdk.CollectionConverters.*
      Files.list(dir).iterator().asScala.toList
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
        .map(_.getFileName.toString.stripSuffix(".json"))
        .filter(_ != "registry")
        .sorted

  def toolsDueForCheck(): List[String] =
    val now = System.currentTimeMillis()
    val days = intervalDaysFromEnv()
    installedTools().filter { base =>
      readState(base) match
        case None     => true
        case Some(st) => isDue(st.lastCheckedAtMillis, now, days)
    }

  def updateAll(force: Boolean = false, dueOnly: Boolean = false): Int =
    val tools =
      if dueOnly && !force then toolsDueForCheck()
      else installedTools()
    if tools.isEmpty then
      if dueOnly then Exit.Ok
      else
        info("no toji-managed tools found (install with: toji install <name>)")
        Exit.Ok
    else
      var failures = 0
      tools.foreach { base =>
        try
          if base == "toji" then selfUpdate(force, ignoreInterval = !dueOnly || force)
          else install(base, "latest", force, ignoreInterval = !dueOnly || force)
        catch
          case e: ValidationError =>
            System.err.println(s"error: ${e.message}")
            failures += 1
      }
      if failures > 0 then Exit.Validation else Exit.Ok

  def maybeAutoUpdate(): Unit =
    try
      if toolsDueForCheck().nonEmpty then
        Process(Seq("sh", "-c", "toji update --all --due-only >/dev/null 2>&1 &")).run()
    catch
      case _: Exception => ()

  /** Install every registry tool that has a release asset for this platform. */
  def installAll(force: Boolean = false): Int =
    val targets =
      Catalog.listAvailable().filter(e => e.status == Catalog.Status.Available || (force && e.canInstall))
    if targets.isEmpty then
      info("nothing to install (run: toji available)")
      Exit.Ok
    else
      var failures = 0
      targets.foreach { e =>
        try install(e.tool.name, "latest", force, ignoreInterval = true)
        catch
          case err: ValidationError =>
            System.err.println(s"error: ${err.message}")
            failures += 1
      }
      if failures > 0 then Exit.Validation else Exit.Ok
