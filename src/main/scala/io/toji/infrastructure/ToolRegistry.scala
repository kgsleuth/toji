package io.toji.infrastructure
import io.toji.cli._

import java.nio.file.{Files, Paths}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

/** Dynamic registry loaded from registry.json. */
object ToolRegistry:

  case class Tool(
    name: String,
    repo: String,          // "owner/repo"
    description: String,
    aliases: List[String] = Nil,
    // How the binary inside the asset is named (without os/arch). Usually same as name.
    binaryBase: String = ""
  ):
    def base: String = if binaryBase.nonEmpty then binaryBase else name

  // Embedded bootstrap — enough to install/update known tools when registry.json
  // cannot be fetched yet (e.g. offline, or before registry is published on toji main).
  private val embedded: List[Tool] = List(
    Tool("toji", "kgsleuth/toji", "The gap-filling cursed tool installer (this binary)."),
    Tool("curtain", "kgsleuth/curtain", "Security curtains: pre-commit + GitHub Actions for secret scanning & private-by-default."),
    Tool("shikigami", "kgsleuth/shikigami", "Observe industry, spec projects, RAG + suggestions for adapting capabilities."),
    Tool("sleuthctl", "kgsleuth/sleuthctl", "Microsoft Sentinel / Defender XDR incident response knowledge and tooling."),
    Tool("kenjaku", "kgsleuth/kenjaku", "Local planning CLI for to-dos/ queues, plan shards, dependency gating, and live MASTER-TODO.md."),
    Tool("yuta", "kgsleuth/yuta", "Yuta — skill copy & technique harvester for the agent skills catalog."),
    Tool("dhruv", "kgsleuth/dhruv", "Perfect-recall RAG library — Scala Native keyword search, RRF, JSONL persistence."),
  )

  // --- Dynamic loading + caching ---

  private case class RegistryData(tools: List[Tool])

  private def cacheDir(): java.nio.file.Path = {
    val base = Option(System.getenv("TOJI_STATE_DIR")).filter(_.nonEmpty)
      .getOrElse(Paths.get(System.getProperty("user.home"), ".local", "state", "toji").toString)
    Paths.get(base)
  }

  private def registryCachePath(): java.nio.file.Path =
    cacheDir().resolve("registry.json")

  private def parseRegistryJson(jsonStr: String): List[Tool] =
    val json = ujson.read(jsonStr)
    json("tools").arr.toList.map { j =>
      Tool(
        name        = j("name").str,
        repo        = j("repo").str,
        description = j("description").str,
        aliases     = j.obj.get("aliases").map(_.arr.toList.map(_.str)).getOrElse(Nil),
        binaryBase  = j.obj.get("binaryBase").map(_.str).getOrElse(""),
      )
    }

  private def fetchRemote(): Option[List[Tool]] =
    localRegistryPaths().flatMap(readRegistryFile).headOption.orElse(fetchRegistryFromGitHub())

  private def localRegistryPaths(): List[java.nio.file.Path] =
    List(
      Paths.get("registry.json"),
      Paths.get("../registry.json"),
      Paths.get("../../registry.json"),
    )

  private def readRegistryFile(p: java.nio.file.Path): Option[List[Tool]] =
    if !Files.isRegularFile(p) then None
    else
      try Some(parseRegistryJson(Files.readString(p)))
      catch case _: Throwable => None

  /** Fetch registry.json from the public toji repo (GitHub contents API). Silent on failure. */
  private def fetchRegistryFromGitHub(): Option[List[Tool]] =
    val body =
      Auth.runGh(Seq("api", "/repos/kgsleuth/toji/contents/registry.json")).orElse {
        Try {
          val url = "https://api.github.com/repos/kgsleuth/toji/contents/registry.json"
          val out = new StringBuilder
          Process(Seq("curl", "-fsSL", url)).!(ProcessLogger(out.append(_), _ => ()))
          out.toString.trim
        }.toOption.filter(_.nonEmpty)
      }
    body.flatMap { raw =>
      Try {
        val json = ujson.read(raw)
        val b64 = json("content").str.replaceAll("\\s", "")
        val decoded = new String(java.util.Base64.getDecoder.decode(b64), java.nio.charset.StandardCharsets.UTF_8)
        parseRegistryJson(decoded)
      }.toOption
    }.filter(_.nonEmpty)

  private def loadFromCache(): Option[List[Tool]] =
    val p = registryCachePath()
    if !Files.isRegularFile(p) then None
    else
      try Some(parseRegistryJson(Files.readString(p)))
      catch case _: Throwable => None

  private def writeCache(tools: List[Tool]): Unit = {
    val p = registryCachePath()
    Files.createDirectories(p.getParent)
    val j = ujson.Obj(
      "schema" -> 1,
      "tools" -> ujson.Arr( (tools.map { t =>
        ujson.Obj(
          "name" -> t.name,
          "repo" -> t.repo,
          "description" -> t.description,
          "aliases" -> ujson.Arr(t.aliases.map(ujson.Str(_))),
          "binaryBase" -> t.binaryBase
        )
      }) : _* )
    )
    Files.writeString(p, ujson.write(j, indent = 2))
  }

  private var cached: Option[List[Tool]] = None

  /** Load the registry (cache → remote refresh → embedded). */
  def load(forceRefresh: Boolean = false): List[Tool] =
    if !forceRefresh && cached.isDefined then return cached.get

    if !forceRefresh then
      loadFromCache().foreach { ts =>
        if ts.nonEmpty then
          cached = Some(ts)
          return ts
      }

    fetchRemote() match
      case Some(ts) if ts.nonEmpty =>
        writeCache(ts)
        cached = Some(ts)
        ts
      case _ =>
        writeCache(embedded)
        cached = Some(embedded)
        embedded

  def refresh(): List[Tool] =
    cached = None
    load(forceRefresh = true)

  /** Merge discovered tools into the cached registry (keeps existing descriptions when set). */
  def mergeAndSave(discovered: List[Tool]): List[Tool] =
    val existing = load()
    val byRepo = scala.collection.mutable.Map.from(existing.map(t => t.repo.toLowerCase -> t))
    discovered.foreach { d =>
      byRepo.get(d.repo.toLowerCase) match
        case Some(old) =>
          byRepo.update(
            d.repo.toLowerCase,
            old.copy(
              description = if old.description.nonEmpty then old.description else d.description,
              binaryBase = if old.binaryBase.nonEmpty then old.binaryBase else d.binaryBase,
            ),
          )
        case None =>
          byRepo(d.repo.toLowerCase) = d
    }
    val merged = byRepo.values.toList.sortBy(_.name)
    writeCache(merged)
    cached = Some(merged)
    merged

  def registryJson(): String =
    val tools = load()
    val j = ujson.Obj(
      "schema" -> 1,
      "updated" -> todayIso(),
      "tools" -> ujson.Arr(tools.map(toolToJson)*),
    )
    ujson.write(j, indent = 2)

  private def todayIso(): String =
    Try(Process(Seq("date", "+%Y-%m-%d")).!!.trim).getOrElse("unknown")

  private def toolToJson(t: Tool): ujson.Obj =
    ujson.Obj(
      "name" -> t.name,
      "repo" -> t.repo,
      "description" -> t.description,
      "aliases" -> ujson.Arr(t.aliases.map(ujson.Str(_))*),
      "binaryBase" -> t.binaryBase,
    )

  // --- Public API (now dynamic) ---

  def all: List[Tool] = load()

  def findByNameOrAlias(q: String): Option[Tool] =
    val key = q.toLowerCase.trim
    load().find { t =>
      t.name.equalsIgnoreCase(key) ||
      t.aliases.exists(_.equalsIgnoreCase(key)) ||
      t.base.equalsIgnoreCase(key)
    }

  def findByRepo(ownerRepo: String): Option[Tool] =
    val norm = ownerRepo.stripPrefix("https://github.com/").stripPrefix("git@github.com:").stripSuffix(".git").trim
    load().find(_.repo.equalsIgnoreCase(norm))

  def search(query: String): List[Tool] =
    if query.trim.isEmpty then load()
    else
      val q = query.toLowerCase
      load().filter { t =>
        t.name.toLowerCase.contains(q) ||
        t.description.toLowerCase.contains(q) ||
        t.repo.toLowerCase.contains(q) ||
        t.aliases.exists(_.toLowerCase.contains(q))
      }

  private val tojiRepo = findByNameOrAlias("toji").map(_.repo).getOrElse("kgsleuth/toji")
  val defaultOwner: String = tojiRepo.split("/")(0)

  def defaultRepoFor(nameOrRepo: String): String =
    findByNameOrAlias(nameOrRepo).map(_.repo).getOrElse {
      if (nameOrRepo.contains("/")) nameOrRepo else s"$defaultOwner/$nameOrRepo"
    }

  def baseFor(nameOrRepo: String): String =
    findByNameOrAlias(nameOrRepo).map(_.base).getOrElse {
      val segs = nameOrRepo.split("/")
      segs.lastOption.getOrElse(nameOrRepo).replaceAll("[^A-Za-z0-9-]", "")
    }
