package io.toji.infrastructure
import io.toji.cli._

import java.nio.file.{Files, Path}
import scala.sys.process.*
import scala.util.Try
import ujson.Value

/** Resolve and download GitHub Release assets.
  *
  * toji is public; tools it installs (kenjaku, curtain, …) are often private.
  * Download order:
  *   1. `gh release download` — reuses `gh auth login` (best for private repos)
  *   2. GitHub REST assets API + Bearer token
  *   3. Public tag URL (public repos only; private repos 404 here)
  */
object GitHubRelease:

  case class Asset(name: String, id: Long, size: Long, downloadUrl: Option[String] = None)
  case class Release(tag: String, assets: List[Asset], htmlUrl: Option[String] = None)

  def resolve(repo: String, version: String, assetName: String): (Release, Asset) =
    val rel = fetchRelease(repo, version)
    resolveFromRelease(rel, assetName)

  def resolveFromRelease(release: Release, assetName: String): (Release, Asset) =
    val asset = release.assets.find(_.name == assetName).getOrElse {
      val available = release.assets.map(_.name).sorted.mkString(", ")
      val seeUrl = release.htmlUrl.getOrElse(s"https://github.com/$repoPlaceholder/releases")
      throw ValidationError(
        s"""Release ${release.tag} has no asset "$assetName".

Available assets: ${if available.nonEmpty then available else "(none)"}
The release may still be publishing — retry in a few minutes or pin an older version.

See $seeUrl"""
      )
    }
    (release, asset)

  def download(repo: String, release: Release, asset: Asset, dest: Path): Unit =
    if tryGhDownload(repo, release.tag, asset.name, dest) then ()
    else if downloadViaApi(repo, asset, dest) then ()
    else if Auth.current == Auth.None && downloadPublic(repo, release.tag, asset.name, dest) then ()
    else downloadFailed(repo, asset.name)

  def fetchRelease(repo: String, version: String): Release =
    val path =
      version match
        case "latest" => s"/repos/$repo/releases/latest"
        case tag      => s"/repos/$repo/releases/tags/$tag"
    parseRelease(apiGetAuthed(s"https://api.github.com$path"), repo, version)

  /** Like fetchRelease but returns None when the release is missing or inaccessible. */
  def fetchReleaseOpt(repo: String, version: String = "latest"): Option[Release] =
    val path =
      version match
        case "latest" => s"/repos/$repo/releases/latest"
        case tag      => s"/repos/$repo/releases/tags/$tag"
    apiGetOpt(s"https://api.github.com$path").map(parseRelease(_, repo, version))

  // --- fetch ---

  private def apiGetOpt(url: String): Option[Value] =
    try Some(apiGetAuthed(url))
    catch
      case _: ValidationError => None
      case _: Exception       => None

  private def apiGetAuthed(url: String): Value =
    Auth.current match
      case Auth.GhCli =>
        val jsonStr = Auth.runGh(Seq("api", url.stripPrefix("https://api.github.com"))).getOrElse(curlGet(url))
        ujson.read(jsonStr)
      case _ =>
        ujson.read(curlGet(url))

  private def curlGet(url: String): String =
    val args = Seq("curl", "-fsSL") ++ Auth.curlAuthArgs ++ Seq("-w", "\n%{http_code}", url)
    val stdout = new StringBuilder
    Process(args).!(ProcessLogger(stdout.append(_), _ => ()))
    val raw = stdout.toString.trim
    if raw.isEmpty then throw ValidationError("GitHub API returned empty response")
    val nl = raw.lastIndexOf('\n')
    val (body, statusStr) =
      if nl >= 0 then (raw.substring(0, nl), raw.substring(nl + 1).trim) else (raw, "0")
    val status = statusStr.toIntOption.getOrElse(0)
    status match
      case 200 => body
      case 404 => throw notFound(extractRepo(url), extractRequested(url))
      case other => throw ValidationError(s"GitHub API error (HTTP $other) for $url")

  private def parseRelease(json: Value, repo: String, requested: String): Release =
    val tag = json.obj.get("tag_name").map(_.str).getOrElse(requested)
    val html = json.obj.get("html_url").map(_.str)
    val assets =
      json.obj.get("assets").map(_.arr.toList).getOrElse(Nil).flatMap { row =>
        val obj = row.obj
        for
          name <- obj.get("name").map(_.str)
          id <- obj.get("id").map(_.num.toLong)
          size <- obj.get("size").map(_.num.toLong)
        yield Asset(name, id, size, obj.get("browser_download_url").map(_.str))
      }
    if assets.isEmpty && json.obj.get("message").exists(m => m.str == "Not Found" || m.str.contains("Not Found")) then
      throw notFound(repo, requested)
    Release(tag, assets, html)

  // --- download ---

  private def tryGhDownload(repo: String, tag: String, assetName: String, dest: Path): Boolean =
    Auth.runGhQuiet(
      Seq(
        "release", "download", tag,
        "--repo", repo,
        "--pattern", assetName,
        "--output", dest.toString,
        "--clobber",
      )
    )

  private def downloadViaApi(repo: String, asset: Asset, dest: Path): Boolean =
    if asset.id <= 0 then false
    else
      val url = s"https://api.github.com/repos/$repo/releases/assets/${asset.id}"
      curlToFile(url, dest, acceptOctetStream = true) == 200

  private def downloadPublic(repo: String, tag: String, assetName: String, dest: Path): Boolean =
    val url = s"https://github.com/$repo/releases/download/$tag/$assetName"
    try curlToFile(url, dest, acceptOctetStream = false) == 200
    catch case _: Exception => false

  private def curlToFile(url: String, dest: Path, acceptOctetStream: Boolean): Int =
    val args =
      Seq("curl", "-fsSL") ++
        Auth.curlAuthArgs ++
        (if acceptOctetStream then Seq("-H", "Accept: application/octet-stream") else Nil) ++
        Seq("-w", "%{http_code}", "-o", dest.toString, url)
    val stdout = new StringBuilder
    val exit = Process(args).!(ProcessLogger(stdout.append(_), _ => ()))
    stdout.toString.trim.toIntOption.getOrElse(if exit == 0 then 200 else 0)

  // --- helpers ---

  private def notFound(repo: String, requested: String): ValidationError =
    val hint =
      if Auth.current == Auth.None then
        s"""If https://github.com/$repo is private, authenticate with:

  gh auth login

or export a PAT:

  export TOJI_GITHUB_TOKEN=ghp_xxx   # or GITHUB_TOKEN / GH_TOKEN
"""
      else
        "Check the tag name, or ensure your gh login / token has access to the repo."
    ValidationError(
      s"""Could not find release "$requested" for $repo.

$hint
See https://github.com/$repo/releases"""
    )

  private def downloadFailed(repo: String, assetName: String): Nothing =
    val hint =
      if Auth.current == Auth.None then
        "Private repos require `gh auth login` or TOJI_GITHUB_TOKEN."
      else
        "Ensure your gh session or token has `repo` scope and read access to the repo."
    throw ValidationError(
      s"""Failed to download $assetName from $repo.

Tried (in order):
  1. gh release download   (preferred — uses your gh auth login session)
  2. GitHub API asset download (Bearer token)
  3. Public releases/download/<tag> URL (public repos only)

$hint

See https://github.com/$repo/releases"""
    )

  private def extractRepo(url: String): String =
    val p = url.stripPrefix("https://api.github.com/repos/")
    val parts = p.split("/")
    if parts.length >= 2 then s"${parts(0)}/${parts(1)}" else "unknown/unknown"

  private def extractRequested(url: String): String =
    if url.endsWith("/releases/latest") then "latest"
    else url.split("/tags/").lastOption.getOrElse("?")

  private val repoPlaceholder = "owner/repo"
