package io.toji.infrastructure
import io.toji.cli._

import java.nio.file.{Files, Paths}
import scala.sys.process.*
import scala.util.Try

/** Probes GitHub releases and discovers installable tools for the current platform. */
object Catalog:

  enum Status:
    case Installed, Available, NoRelease, NoAsset, Inaccessible

  case class Entry(
    tool: ToolRegistry.Tool,
    status: Status,
    releaseTag: Option[String] = None,
    installedVersion: Option[String] = None,
  ):
    def canInstall: Boolean =
      status == Status.Available || status == Status.Installed

  private val PlatformSuffixes =
    List("-linux-x86_64", "-linux-aarch64", "-macos-x86_64", "-macos-aarch64")

  def defaultOrg: String =
    Option(System.getenv("TOJI_DEFAULT_ORG")).filter(_.nonEmpty).getOrElse(ToolRegistry.defaultOwner)

  def listAvailable(tools: List[ToolRegistry.Tool] = ToolRegistry.all): List[Entry] =
    tools.map(probe)

  def probe(tool: ToolRegistry.Tool): Entry =
    val base = tool.base
    val assetName =
      try Platform.detectAsset(base).value
      catch case _: ValidationError => return Entry(tool, Status.NoAsset)

    GitHubRelease.fetchReleaseOpt(tool.repo, "latest") match
      case None =>
        Entry(tool, if Auth.current == Auth.None then Status.Inaccessible else Status.NoRelease)
      case Some(release) =>
        val hasAsset = release.assets.exists(_.name == assetName)
        val dest = defaultBinDir.resolve(base)
        val onPath = isOnPath(base)
        val installedVer =
          if Files.isRegularFile(dest) then Try(readVersion(dest)).toOption
          else onPath.flatMap(p => Try(readVersion(Paths.get(p))).toOption)

        val status =
          if !hasAsset then Status.NoAsset
          else if installedVer.isDefined then Status.Installed
          else Status.Available

        Entry(
          tool = tool,
          status = status,
          releaseTag = Some(release.tag),
          installedVersion = installedVer,
        )

  /** Discover repos under an org/user that publish a release asset for this platform. */
  def scanOrganization(org: String = defaultOrg): List[ToolRegistry.Tool] =
    listGitHubRepos(org).flatMap(repoToTool)

  def scanAndMergeRegistry(org: String = defaultOrg): List[ToolRegistry.Tool] =
    val discovered = scanOrganization(org)
    ToolRegistry.mergeAndSave(discovered)

  private def repoToTool(repo: GitHubRepo): Option[ToolRegistry.Tool] =
    GitHubRelease.fetchReleaseOpt(repo.fullName, "latest") match
      case None => None
      case Some(release) =>
        val assetNames = release.assets.map(_.name)
        val platformAsset = assetNames.find(isPlatformBinary)
        platformAsset match
          case None => None
          case Some(asset) =>
            val binaryBase = baseFromAssetName(asset).getOrElse(repo.name)
            Some(
              ToolRegistry.Tool(
                name = repo.name,
                repo = repo.fullName,
                description = repo.description.getOrElse(s"${repo.name} CLI"),
                binaryBase = if binaryBase != repo.name then binaryBase else "",
              )
            )

  private def isPlatformBinary(name: String): Boolean =
    PlatformSuffixes.exists(name.endsWith)

  private def baseFromAssetName(asset: String): Option[String] =
    PlatformSuffixes.find(asset.endsWith).map(asset.stripSuffix(_))

  private case class GitHubRepo(name: String, fullName: String, description: Option[String])

  private def listGitHubRepos(org: String): List[GitHubRepo] =
    val fromUser = fetchRepos(s"/users/$org/repos")
    if fromUser.nonEmpty then fromUser else fetchRepos(s"/orgs/$org/repos")

  private def fetchRepos(path: String): List[GitHubRepo] =
    Auth.runGh(Seq("api", path, "-f", "per_page=100", "-f", "type=owner")) match
      case None => Nil
      case Some(raw) =>
        Try {
          ujson.read(raw).arr.toList.flatMap { row =>
            val obj = row.obj
            for
              name <- obj.get("name").map(_.str)
              full <- obj.get("full_name").map(_.str)
            yield GitHubRepo(
              name,
              full,
              obj.get("description").map(_.str).filter(_.nonEmpty),
            )
          }
        }.getOrElse(Nil)

  private def defaultBinDir =
    val dir = Option(System.getenv("TOJI_INSTALL_DIR")).filter(_.nonEmpty)
      .getOrElse(Paths.get(System.getProperty("user.home"), ".local", "bin").toString)
    Paths.get(dir)

  private def isOnPath(base: String): Option[String] =
    Try(Process(Seq("sh", "-c", s"command -v $base")).!!.trim).toOption.filter(_.nonEmpty)

  private def readVersion(bin: java.nio.file.Path): String =
    Try(Process(Seq(bin.toString, "--version")).!!.trim)
      .orElse(Try(Process(Seq(bin.toString, "-V")).!!.trim))
      .getOrElse("")

  def formatStatus(e: Entry): String =
    e.status match
      case Status.Installed   => "installed"
      case Status.Available   => "available"
      case Status.NoRelease   => "no release"
      case Status.NoAsset     => "no binary for this platform"
      case Status.Inaccessible => "needs gh auth"

  def formatEntryLine(e: Entry): String =
    val tag = e.releaseTag.map(t => f"  $t%-10s").getOrElse(f"  ${"—"}%-10s")
    val ver =
      e.installedVersion.filter(_.nonEmpty).map(v => s" ($v)").getOrElse("")
    f"  ${e.tool.name}%-14s$tag${formatStatus(e)}%-22s${e.tool.repo}$ver"
