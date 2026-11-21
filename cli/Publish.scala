// `scalino publish-local` and `scalino publish`: the library's artifacts, as sbt / scala-cli publish
// them, for another project to depend on with `//> using dep org::name::version`.
//
// A publication is the main jar (what scalino-dotc wrote: `.tasty` plus `.nir` for Scala Native or
// `.sjsir` for Scala.js -- there is no JVM backend here, so no `.class` -- and the resource
// directories), a sources jar, a javadoc jar (manifest only: there is no scaladoc without a JVM;
// Central accepts it) and a POM. The module is named `<name>_native0.5_3` / `<name>_sjs1_3`, and
// the POM lists the platform runtime libraries sbt-scala-native / sbt-scalajs would, so sbt, Mill
// and scala-cli consumers see the same graph as for an sbt-published library.
//
// Everything is written in-process (zip, POM, ivy.xml, MD5/SHA-1) except signing (`gpg`) and
// uploads (`curl`). Targets:
//  - publish-local: ivy layout in ~/.ivy2/local, coursier's default `ivy2Local` repository, so
//    any scalino project on the machine resolves it with no extra repository (`--m2`: Maven layout
//    in ~/.m2/repository instead);
//  - publish to `central` (default): Sonatype Central Portal, one signed bundle uploaded through
//    its publisher API and followed until validated/published; `-SNAPSHOT` versions go to the
//    Central snapshots repository;
//  - publish to another `https://` repository: Maven deploy by HTTP PUT; to a path or `file:` URL:
//    the Maven layout written there.

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import ScalinoCli.{BuildFailed, Color, die, exitBuildFailure, fail, readFile, runCaptureStdout, runInherited}

object Publish:

  case class Developer(id: String, name: String, url: String, email: Option[String])
  case class Scm(url: String, connection: String, developerConnection: String)
  case class License(name: String, url: String)

  /** POM-level metadata, resolved from `//> using publish.*`, flags and git. */
  case class Meta(
    organization: String,
    name: String,
    moduleName: String,
    version: String,
    description: Option[String],
    url: Option[String],
    license: Option[License],
    scm: Option[Scm],
    developers: List[Developer],
    versionScheme: Option[String]
  )

  /** One dependency of the published module, as it goes into the POM / ivy.xml. */
  case class Dep(org: String, name: String, version: String, scope: String = "compile")

  /** One published file: `<module>-<version>[-<classifier>].<ext>` in Maven layout. */
  case class Artifact(classifier: Option[String], ext: String, bytes: Array[Byte])

  // ---- options ----------------------------------------------------------------------------------

  /** What `publish.*` directives and the publish flags set; flags win over directives. */
  case class Settings(
    organization: Option[String] = None,
    name: Option[String] = None,
    moduleName: Option[String] = None,
    version: Option[String] = None,
    computeVersion: Option[String] = None,
    url: Option[String] = None,
    license: Option[String] = None,
    vcs: Option[String] = None,
    description: Option[String] = None,
    developers: List[String] = Nil,
    versionScheme: Option[String] = None,
    repository: Option[String] = None,
    gpgKey: Option[String] = None,
    gpgOptions: List[String] = Nil,
    secretKey: Option[String] = None,
    secretKeyPassword: Option[String] = None,
    signer: Option[String] = None,
    user: Option[String] = None,
    password: Option[String] = None,
    sources: Option[Boolean] = None,
    doc: Option[Boolean] = None
  ):
    /** `this` with every field `other` sets taking over. */
    def overriddenBy(other: Settings): Settings = Settings(
      other.organization.orElse(organization), other.name.orElse(name), other.moduleName.orElse(moduleName),
      other.version.orElse(version), other.computeVersion.orElse(computeVersion), other.url.orElse(url),
      other.license.orElse(license), other.vcs.orElse(vcs), other.description.orElse(description),
      if other.developers.nonEmpty then other.developers else developers,
      other.versionScheme.orElse(versionScheme), other.repository.orElse(repository), other.gpgKey.orElse(gpgKey),
      if other.gpgOptions.nonEmpty then other.gpgOptions else gpgOptions,
      other.secretKey.orElse(secretKey), other.secretKeyPassword.orElse(secretKeyPassword), other.signer.orElse(signer),
      other.user.orElse(user), other.password.orElse(password), other.sources.orElse(sources), other.doc.orElse(doc)
    )

  /** Flags only `publish`/`publish-local` take, pulled out before the shared `parseRunOpts`. */
  case class Flags(
    settings: Settings = Settings(),
    ivy2Home: Option[String] = None,
    m2: Boolean = false,
    m2Home: Option[String] = None,
    dummy: Boolean = false,
    manualRelease: Boolean = false
  )

  private def boolValue(flag: String, v: String): Boolean = v.toLowerCase match
    case "true" | "yes" | "1" => true
    case "false" | "no" | "0" => false
    case _ => die(s"$flag=$v: expected true or false")

  /** Splits `args` into publish flags and what `parseRunOpts` handles. */
  def extractFlags(args: Array[String]): (Flags, Array[String]) =
    var f = Flags()
    def s(update: Settings => Settings): Unit = f = f.copy(settings = update(f.settings))
    val rest = scala.collection.mutable.ArrayBuffer.empty[String]
    var i = 0
    def value(flag: String): String =
      if i + 1 >= args.length then die(s"$flag needs a value")
      i += 1
      args(i)
    var inProgArgs = false
    while i < args.length do
      val a = args(i)
      if inProgArgs then rest += a
      else a match
        case "--" => inProgArgs = true; rest += a
        case "--organization" => val v = value(a); s(_.copy(organization = Some(v)))
        case "--name" => val v = value(a); s(_.copy(name = Some(v)))
        case "--module-name" => val v = value(a); s(_.copy(moduleName = Some(v)))
        case "--project-version" => val v = value(a); s(_.copy(version = Some(v)))
        case "--compute-version" => val v = value(a); s(_.copy(computeVersion = Some(v)))
        case "--url" => val v = value(a); s(_.copy(url = Some(v)))
        case "--license" => val v = value(a); s(_.copy(license = Some(v)))
        case "--vcs" | "--scm" => val v = value(a); s(_.copy(vcs = Some(v)))
        case "--description" => val v = value(a); s(_.copy(description = Some(v)))
        case "--developer" => val v = value(a); s(st => st.copy(developers = st.developers :+ v))
        case "--version-scheme" => val v = value(a); s(_.copy(versionScheme = Some(v)))
        case "-R" | "--publish-repository" | "--publish-repo" => val v = value(a); s(_.copy(repository = Some(v)))
        case "--gpg-key" => val v = value(a); s(_.copy(gpgKey = Some(v)))
        case "--gpg-option" => val v = value(a); s(st => st.copy(gpgOptions = st.gpgOptions :+ v))
        case "--secret-key" => val v = value(a); s(_.copy(secretKey = Some(v)))
        case "--secret-key-password" => val v = value(a); s(_.copy(secretKeyPassword = Some(v)))
        case "--signer" => val v = value(a); s(_.copy(signer = Some(v)))
        case "--user" => val v = value(a); s(_.copy(user = Some(v)))
        case "--password" => val v = value(a); s(_.copy(password = Some(v)))
        case "--sources" => s(_.copy(sources = Some(true)))
        case "--doc" => s(_.copy(doc = Some(true)))
        case x if x.startsWith("--sources=") => s(_.copy(sources = Some(boolValue("--sources", x.drop(10)))))
        case x if x.startsWith("--doc=") => s(_.copy(doc = Some(boolValue("--doc", x.drop(6)))))
        case "--ivy2-home" => f = f.copy(ivy2Home = Some(value(a)))
        case "--m2" => f = f.copy(m2 = true)
        case "--m2-home" => f = f.copy(m2Home = Some(value(a)), m2 = true)
        case "--dummy" => f = f.copy(dummy = true)
        case "--manual-release" => f = f.copy(manualRelease = true)
        case _ => rest += a
      i += 1
    (f, rest.toArray)

  // ---- directives -------------------------------------------------------------------------------

  private val publishRe = """//>\s*using\s+publish\.(\S+)(.*)$""".r
  private val quotedRe = """"([^"]*)"""".r

  /** Every `publish.<key>` scala-cli knows that scalino uses; `publish.ci.<key>` applies only when
   *  `$CI` is set (and then wins over `publish.<key>`), as in scala-cli. */
  val DirectiveKeys: Set[String] = Set(
    "organization", "name", "moduleName", "version", "computeVersion", "url", "license",
    "versionControl", "scm", "vcs", "description", "developer", "developers", "versionScheme",
    "repository", "gpgKey", "gpgOptions", "gpgOption", "secretKey", "secretKeyPassword", "signer",
    "user", "password", "doc", "sources"
  )

  def isKnownDirective(key: String): Boolean =
    key.startsWith("publish.") && DirectiveKeys(key.stripPrefix("publish.").stripPrefix("ci."))

  private def onCi: Boolean = Option(System.getenv("CI")).exists(v => v.nonEmpty && v != "false")

  def parseDirectiveSettings(sources: List[Path]): Settings =
    var plain = Settings()
    var ci = Settings()
    for src <- sources; raw <- readFile(src).linesIterator do
      val line = raw.trim
      if line.startsWith("//>") then
        publishRe.findFirstMatchIn(line).foreach { m =>
          val (isCi, key) =
            if m.group(1).startsWith("ci.") then (true, m.group(1).drop(3)) else (false, m.group(1))
          val rest = m.group(2)
          val quoted = quotedRe.findAllMatchIn(rest).map(_.group(1)).toList
          // Unquoted values are split on whitespace, except for the free-text ones.
          val values =
            if quoted.nonEmpty then quoted
            else if key == "description" then List(rest.trim).filter(_.nonEmpty)
            else rest.trim.split("""\s+""").toList.filter(_.nonEmpty)
          def one = values.lastOption
          def bool = values.headOption.forall(_.toLowerCase != "false")
          def set(st: Settings): Settings = key match
            case "organization" => st.copy(organization = one)
            case "name" => st.copy(name = one)
            case "moduleName" => st.copy(moduleName = one)
            case "version" => st.copy(version = one)
            case "computeVersion" => st.copy(computeVersion = one)
            case "url" => st.copy(url = one)
            case "license" => st.copy(license = one)
            case "versionControl" | "scm" | "vcs" => st.copy(vcs = one)
            case "description" => st.copy(description = one)
            case "developer" | "developers" => st.copy(developers = st.developers ++ values)
            case "versionScheme" => st.copy(versionScheme = one)
            case "repository" => st.copy(repository = one)
            case "gpgKey" => st.copy(gpgKey = one)
            case "gpgOptions" | "gpgOption" => st.copy(gpgOptions = st.gpgOptions ++ values)
            case "secretKey" => st.copy(secretKey = one)
            case "secretKeyPassword" => st.copy(secretKeyPassword = one)
            case "signer" => st.copy(signer = one)
            case "user" => st.copy(user = one)
            case "password" => st.copy(password = one)
            case "doc" => st.copy(doc = Some(bool))
            case "sources" => st.copy(sources = Some(bool))
            case _ => st // reported by parseDirectives
          if isCi then ci = set(ci) else plain = set(plain)
        }
    if onCi then plain.overriddenBy(ci) else plain

  /** A secret given the scala-cli way: `env:NAME`, `file:path`, `value:literal` (or a bare literal). */
  def secretValue(spec: String, what: String): String =
    if spec.startsWith("env:") then
      val name = spec.drop(4)
      Option(System.getenv(name)).filter(_.nonEmpty).getOrElse(fail(s"$what: environment variable $name is not set"))
    else if spec.startsWith("file:") then
      try readFile(Paths.get(spec.drop(5))).trim
      catch case e: java.io.IOException => fail(s"$what: can't read ${spec.drop(5)}: ${e.getMessage}")
    else spec.stripPrefix("value:")

  // ---- metadata ---------------------------------------------------------------------------------

  /** Output of a git command run in the project directory, or None outside a repository. */
  private def git(args: String*): Option[String] =
    try
      val (code, out) = runCaptureStdoutQuiet("git" :: args.toList)
      Option.when(code == 0)(out.trim).filter(_.nonEmpty)
    catch case _: java.io.IOException => None

  /** Like `runCaptureStdout` with stderr dropped (git's "fatal: no tag" is expected noise here). */
  private def runCaptureStdoutQuiet(cmd: List[String]): (Int, String) =
    val pb = new ProcessBuilder(cmd.asJava)
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    val p = pb.start()
    val out = ScalinoCli.readAll(p.getInputStream)
    (p.waitFor(), out)

  /** `github.com/<owner>/<repo>` from the `origin` remote, in either URL form. */
  private def githubRepo(): Option[(String, String)] =
    val re = """(?:https?://|ssh://)?(?:git@)?github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?/?""".r
    git("remote", "get-url", "origin").flatMap {
      case re(owner, repo) => Some((owner, repo))
      case _ => None
    }

  /** scala-cli's `computeVersion`: `git:tag` (HEAD's tag, `v` dropped) or `git:dynver` (sbt-dynver
   *  style: the tag on a clean tagged commit, `<tag>+<n>-<sha>-SNAPSHOT` past it). */
  def computeVersion(spec: String): String = spec match
    case "git" | "git:tag" =>
      git("describe", "--tags", "--exact-match").map(_.stripPrefix("v"))
        .getOrElse(fail("computeVersion git:tag: HEAD has no tag"))
    case "git:dynver" =>
      val describe = """v?(.+)-(\d+)-g([0-9a-f]+)(-dirty)?""".r
      git("describe", "--tags", "--long", "--abbrev=8", "--dirty") match
        case Some(describe(tag, n, sha, dirty)) =>
          if n == "0" && dirty == null then tag else s"$tag+$n-$sha-SNAPSHOT"
        case _ =>
          val sha = git("rev-parse", "--short=8", "HEAD").getOrElse(fail("computeVersion git:dynver: not a git repository"))
          s"0.0.0+$sha-SNAPSHOT"
    case other => fail(s"computeVersion: unsupported '$other' (expected git:tag or git:dynver)")

  /** `Apache-2.0` (an SPDX id, as scala-cli takes it) or `name:url`. */
  def parseLicense(v: String): License =
    val i = v.indexOf(":http")
    if i > 0 then License(v.substring(0, i), v.substring(i + 1))
    else License(v, s"https://spdx.org/licenses/$v.html")

  /** `github:owner/repo`, or `url|connection|developerConnection`, as scala-cli takes them. */
  def parseScm(v: String): Scm =
    if v.startsWith("github:") then
      val path = v.drop(7).stripSuffix(".git")
      Scm(s"https://github.com/$path", s"scm:git:github.com/$path.git", s"scm:git:git@github.com:$path.git")
    else v.split('|') match
      case Array(url, conn, dev) => Scm(url, conn, dev)
      case Array(url) => Scm(url, s"scm:git:$url", s"scm:git:$url")
      case _ => fail(s"publish.versionControl '$v': expected github:owner/repo or url|connection|developerConnection")

  /** `id|name|url` or `id|name|url|email`. */
  def parseDeveloper(v: String): Developer = v.split('|').toList match
    case List(id, name, url) => Developer(id, name, url, None)
    case List(id, name, url, email) => Developer(id, name, url, Some(email))
    case _ => fail(s"publish.developer '$v': expected id|name|url or id|name|url|email")

  def platformSuffix(js: Boolean): String =
    if js then "_sjs1_3" else s"_native${BuildInfo.nativeBinaryVersion}_3"

  def resolveMeta(st: Settings, js: Boolean, projectDir: Path, local: Boolean): Meta =
    val gh = githubRepo()
    val organization = st.organization
      .orElse(gh.map { case (owner, _) => s"io.github.${owner.toLowerCase}" })
      .orElse(Option.when(local)("default"))
      .getOrElse(fail("no organization: set //> using publish.organization (or --organization)"))
    val dirName = Option(projectDir.toAbsolutePath.normalize.getFileName).map(_.toString).getOrElse("project")
    val name = st.name.getOrElse(dirName.toLowerCase.map(c => if c.isLetterOrDigit || c == '-' || c == '.' then c else '-'))
    val version = st.version
      .orElse(st.computeVersion.map(computeVersion))
      .orElse(if local then Some("0.1.0-SNAPSHOT") else None)
      .getOrElse(fail("no version: set //> using publish.version or publish.computeVersion git:tag (or --project-version)"))
    Meta(
      organization = organization,
      name = name,
      moduleName = st.moduleName.getOrElse(name + platformSuffix(js)),
      version = version,
      description = st.description,
      url = st.url.orElse(gh.map { case (o, r) => s"https://github.com/$o/$r" }),
      license = st.license.map(parseLicense),
      scm = st.vcs.map(parseScm).orElse(gh.map { case (o, r) => parseScm(s"github:$o/$r") }),
      developers = st.developers.map(parseDeveloper),
      versionScheme = st.versionScheme
    )

  /** The Scala Native version written into POMs for the runtime libraries. This toolchain's own
   *  is built from main (`0.5.13-SNAPSHOT`), which a released library must not depend on, so a
   *  snapshot maps to the release before it; SCALINO_PUBLISH_NATIVE_VERSION overrides. */
  def nativeRuntimeVersion: String =
    Option(System.getenv("SCALINO_PUBLISH_NATIVE_VERSION")).filter(_.nonEmpty).getOrElse {
      val v = BuildInfo.nativeVersion
      val snap = """(\d+)\.(\d+)\.(\d+)-SNAPSHOT""".r
      v match
        case snap(ma, mi, pa) if pa.toInt > 0 => s"$ma.$mi.${pa.toInt - 1}"
        case _ => v
    }

  /** What sbt-scala-native / sbt-scalajs put in every POM for the platform itself. */
  def runtimeDeps(js: Boolean): List[Dep] =
    val sv = BuildInfo.scalaVersion
    if js then List(
      Dep("org.scala-lang", "scala3-library_sjs1_3", sv),
      Dep("org.scala-js", "scalajs-library_2.13", BuildInfo.scalaJsVersion))
    else
      val nv = nativeRuntimeVersion
      val bin = BuildInfo.nativeBinaryVersion
      Dep("org.scala-lang", "scala3-library_3", sv) ::
        Dep("org.scala-native", s"scala3lib_native${bin}_3", s"$sv+$nv") ::
        List("nativelib", "clib", "posixlib", "windowslib", "javalib", "auxlib").map(l => Dep("org.scala-native", s"${l}_native${bin}_3", nv))

  /** A `//> using dep` (already turned into `org:name:version` by `toCoursierCoord`). */
  def depOf(coord: String, scope: String): Option[Dep] =
    coord.takeWhile(_ != ',').split(':') match
      case Array(org, name, version) => Some(Dep(org, name, version, scope))
      case Array(org, name, version, _*) => Some(Dep(org, name, version, scope))
      case _ =>
        System.err.println(Color.warn(s"scalino: warning: dependency '$coord' has no version -- left out of the POM"))
        None

  // ---- file formats -----------------------------------------------------------------------------

  private def xml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def pom(m: Meta, deps: List[Dep]): String =
    val sb = new StringBuilder
    def tag(indent: String, name: String, v: String): Unit = sb.append(s"$indent<$name>${xml(v)}</$name>\n")
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">""").append('\n')
    tag("  ", "modelVersion", "4.0.0")
    tag("  ", "groupId", m.organization)
    tag("  ", "artifactId", m.moduleName)
    tag("  ", "version", m.version)
    tag("  ", "packaging", "jar")
    tag("  ", "name", m.name)
    tag("  ", "description", m.description.getOrElse(m.name))
    m.url.foreach(tag("  ", "url", _))
    m.license.foreach { l =>
      sb.append("  <licenses>\n    <license>\n")
      tag("      ", "name", l.name); tag("      ", "url", l.url); tag("      ", "distribution", "repo")
      sb.append("    </license>\n  </licenses>\n")
    }
    m.scm.foreach { s =>
      sb.append("  <scm>\n")
      tag("    ", "url", s.url); tag("    ", "connection", s.connection); tag("    ", "developerConnection", s.developerConnection)
      sb.append("  </scm>\n")
    }
    if m.developers.nonEmpty then
      sb.append("  <developers>\n")
      for d <- m.developers do
        sb.append("    <developer>\n")
        tag("      ", "id", d.id); tag("      ", "name", d.name); tag("      ", "url", d.url)
        d.email.foreach(tag("      ", "email", _))
        sb.append("    </developer>\n")
      sb.append("  </developers>\n")
    m.versionScheme.foreach { v =>
      sb.append("  <properties>\n"); tag("    ", "info.versionScheme", v); sb.append("  </properties>\n")
    }
    if deps.nonEmpty then
      sb.append("  <dependencies>\n")
      for d <- deps do
        sb.append("    <dependency>\n")
        tag("      ", "groupId", d.org); tag("      ", "artifactId", d.name); tag("      ", "version", d.version)
        if d.scope != "compile" then tag("      ", "scope", d.scope)
        sb.append("    </dependency>\n")
      sb.append("  </dependencies>\n")
    sb.append("</project>\n")
    sb.toString

  /** ivy.xml in the shape sbt writes to ~/.ivy2/local, which is what coursier reads there. */
  def ivyXml(m: Meta, deps: List[Dep], artifacts: List[Artifact], publication: String): String =
    val sb = new StringBuilder
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">""").append('\n')
    val scheme = m.versionScheme.map(v => s""" e:info.versionScheme="${xml(v)}"""").getOrElse("")
    sb.append(s"""  <info organisation="${xml(m.organization)}" module="${xml(m.moduleName)}" revision="${xml(m.version)}" status="${if m.version.endsWith("SNAPSHOT") then "integration" else "release"}" publication="$publication"$scheme>\n""")
    m.description.foreach(d => sb.append(s"""    <description${m.url.map(u => s""" homepage="${xml(u)}"""").getOrElse("")}>${xml(d)}</description>\n"""))
    sb.append("  </info>\n")
    sb.append("  <configurations>\n")
    for (name, ext) <- List("compile" -> "", "runtime" -> "compile", "test" -> "runtime", "provided" -> "", "optional" -> "",
                           "sources" -> "", "docs" -> "", "pom" -> "") do
      val e = if ext.nonEmpty then s""" extends="$ext"""" else ""
      sb.append(s"""    <conf name="$name" visibility="public" description=""$e/>\n""")
    sb.append("  </configurations>\n  <publications>\n")
    for a <- artifacts do
      val (tpe, conf) = a.classifier match
        case Some("sources") => ("src", "sources")
        case Some("javadoc") => ("doc", "docs")
        case _ => if a.ext == "pom" then ("pom", "pom") else ("jar", "compile")
      val cls = a.classifier.map(c => s""" e:classifier="$c"""").getOrElse("")
      sb.append(s"""    <artifact name="${xml(m.moduleName)}" type="$tpe" ext="${a.ext}" conf="$conf"$cls/>\n""")
    sb.append("  </publications>\n  <dependencies>\n")
    for d <- deps do
      val conf = if d.scope == "provided" then "provided->default(compile)" else "compile->default(compile)"
      sb.append(s"""    <dependency org="${xml(d.org)}" name="${xml(d.name)}" rev="${xml(d.version)}" conf="$conf"/>\n""")
    sb.append("  </dependencies>\n</ivy-module>\n")
    sb.toString

  // Zip (jar) writer: deflated entries, a fixed timestamp and sorted entries, so publishing the same
  // inputs twice gives the same bytes (and the same checksums).

  // 2010-01-01 00:00 in DOS date format, as sbt's reproducible jars.
  private val DosDate = ((2010 - 1980) << 9) | (1 << 5) | 1

  def zip(entries: List[(String, Array[Byte])]): Array[Byte] =
    if entries.size > 0xffff then fail(s"too many files for a jar (${entries.size})")
    val out = new java.io.ByteArrayOutputStream()
    val central = new java.io.ByteArrayOutputStream()
    def le16(o: java.io.ByteArrayOutputStream, v: Int): Unit = { o.write(v & 0xff); o.write((v >>> 8) & 0xff) }
    def le32(o: java.io.ByteArrayOutputStream, v: Long): Unit = { le16(o, (v & 0xffff).toInt); le16(o, ((v >>> 16) & 0xffff).toInt) }
    for (name, data) <- entries do
      val isDir = name.endsWith("/")
      val crc = new java.util.zip.CRC32()
      crc.update(data, 0, data.length)
      val deflated =
        if data.isEmpty then data
        else
          val d = new java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
          d.setInput(data)
          d.finish()
          val buf = new java.io.ByteArrayOutputStream()
          val chunk = new Array[Byte](65536)
          while !d.finished() do
            val n = d.deflate(chunk)
            buf.write(chunk, 0, n)
          d.end()
          buf.toByteArray
      val method = if data.isEmpty then 0 else 8
      val nameBytes = name.getBytes(UTF_8)
      val offset = out.size()
      def header(o: java.io.ByteArrayOutputStream): Unit =
        le16(o, 0x0800) // UTF-8 names
        le16(o, method)
        le16(o, 0) // time
        le16(o, DosDate)
        le32(o, crc.getValue)
        le32(o, deflated.length.toLong)
        le32(o, data.length.toLong)
        le16(o, nameBytes.length)
        le16(o, 0) // extra
      le32(out, 0x04034b50L); le16(out, 20); header(out)
      out.write(nameBytes); out.write(deflated)
      le32(central, 0x02014b50L); le16(central, 20); le16(central, 20); header(central)
      le16(central, 0); le16(central, 0); le16(central, 0) // comment, disk, internal attributes
      le32(central, if isDir then 0x10L else 0L)
      le32(central, offset.toLong)
      central.write(nameBytes)
    val cdOffset = out.size()
    out.write(central.toByteArray)
    le32(out, 0x06054b50L); le16(out, 0); le16(out, 0)
    le16(out, entries.size); le16(out, entries.size)
    le32(out, central.size().toLong); le32(out, cdOffset.toLong); le16(out, 0)
    out.toByteArray

  def manifest(extra: List[(String, String)] = Nil): Array[Byte] =
    (("Manifest-Version" -> "1.0") :: ("Created-By" -> s"scalino ${BuildInfo.scalinoVersion}") :: extra)
      .map { case (k, v) => s"$k: $v\r\n" }.mkString.concat("\r\n").getBytes(UTF_8)

  /** A jar: `META-INF/MANIFEST.MF` first (where `JarInputStream` looks), then `files` sorted. */
  def jar(files: List[(String, Array[Byte])], manifestExtra: List[(String, String)] = Nil): Array[Byte] =
    val byName = files.filter(_._1 != "META-INF/MANIFEST.MF").groupBy(_._1).map(_._2.head).toList.sortBy(_._1)
    val dirs = byName.flatMap { case (n, _) =>
      n.split('/').toList.dropRight(1).inits.toList.filter(_.nonEmpty).map(_.mkString("", "/", "/"))
    }.distinct.filter(_ != "META-INF/").sorted
    zip(("META-INF/", Array.emptyByteArray) :: ("META-INF/MANIFEST.MF", manifest(manifestExtra)) ::
      (dirs.map(_ -> Array.emptyByteArray) ++ byName).sortBy(_._1))

  /** Regular files under `dir`, as jar entry names (`/`-separated, relative). */
  def filesUnder(dir: Path): List[(String, Path)] =
    if !Files.isDirectory(dir) then Nil
    else
      val stream = Files.walk(dir)
      try
        stream.iterator().asScala.filter(Files.isRegularFile(_)).map { p =>
          dir.relativize(p).iterator().asScala.map(_.toString).mkString("/") -> p
        }.filterNot(_._1.split('/').exists(_.startsWith("."))).toList
      finally stream.close()

  // SHA-1 (FIPS 180-4), for the `.sha1` files every repository wants: no MessageDigest in this javalib.
  def sha1Hex(data: Array[Byte]): String =
    val padded = new Array[Byte](((data.length + 8) / 64 + 1) * 64)
    System.arraycopy(data, 0, padded, 0, data.length)
    padded(data.length) = 0x80.toByte
    val bitLen = data.length.toLong * 8
    for k <- 0 until 8 do padded(padded.length - 1 - k) = ((bitLen >>> (8 * k)) & 0xff).toByte
    var h0 = 0x67452301; var h1 = 0xefcdab89; var h2 = 0x98badcfe; var h3 = 0x10325476; var h4 = 0xc3d2e1f0
    val w = new Array[Int](80)
    var block = 0
    while block < padded.length do
      var t = 0
      while t < 16 do
        val o = block + t * 4
        w(t) = ((padded(o) & 0xff) << 24) | ((padded(o + 1) & 0xff) << 16) | ((padded(o + 2) & 0xff) << 8) | (padded(o + 3) & 0xff)
        t += 1
      while t < 80 do
        w(t) = Integer.rotateLeft(w(t - 3) ^ w(t - 8) ^ w(t - 14) ^ w(t - 16), 1)
        t += 1
      var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
      t = 0
      while t < 80 do
        val (f, k) =
          if t < 20 then ((b & c) | (~b & d), 0x5a827999)
          else if t < 40 then (b ^ c ^ d, 0x6ed9eba1)
          else if t < 60 then ((b & c) | (b & d) | (c & d), 0x8f1bbcdc)
          else (b ^ c ^ d, 0xca62c1d6)
        val tmp = Integer.rotateLeft(a, 5) + f + e + k + w(t)
        e = d; d = c; c = Integer.rotateLeft(b, 30); b = a; a = tmp
        t += 1
      h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
      block += 64
    List(h0, h1, h2, h3, h4).map(h => f"$h%08x").mkString

  // ---- signing ----------------------------------------------------------------------------------

  /** Detached ASCII-armored signatures with `gpg`. With a secret key given (the armored key, or its
   *  base64, as CI secrets usually hold it), it is imported into a throwaway GNUPGHOME first, so the
   *  user's keyring is never touched. */
  final class Signer(keyId: Option[String], secretKey: Option[String], passphrase: Option[String], options: List[String]):
    private val gpg = ScalinoCli.findOnPathOpt("gpg").getOrElse(fail("signing needs gpg on the PATH (or --signer none)"))
    private val work = Files.createTempDirectory("scalino-sign")
    private val home: Option[Path] = secretKey.map { key =>
      val h = Files.createDirectories(work.resolve("gnupg"))
      setOwnerOnly(h, dir = true)
      val armored =
        if key.contains("-----BEGIN PGP") then key
        else
          try new String(java.util.Base64.getMimeDecoder.decode(key.trim), UTF_8)
          catch case _: IllegalArgumentException => fail("publish.secretKey is neither an armored PGP key nor its base64")
      val keyFile = work.resolve("key.asc")
      Files.write(keyFile, armored.getBytes(UTF_8))
      setOwnerOnly(keyFile, dir = false)
      val (code, _) = runCaptureStdout(List(gpg, "--homedir", h.toString, "--batch", "--quiet", "--import", keyFile.toString))
      Files.delete(keyFile)
      if code != 0 then fail("gpg could not import the secret key")
      h
    }
    private val passFile: Option[Path] = passphrase.map { p =>
      val f = work.resolve("pass")
      Files.write(f, p.getBytes(UTF_8))
      setOwnerOnly(f, dir = false)
      f
    }

    private def setOwnerOnly(p: Path, dir: Boolean): Unit =
      try
        import java.nio.file.attribute.PosixFilePermission.*
        val perms = if dir then Set(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE) else Set(OWNER_READ, OWNER_WRITE)
        Files.setPosixFilePermissions(p, perms.asJava)
      catch case _: UnsupportedOperationException => () // Windows: the temp directory is per-user already

    def sign(data: Array[Byte]): Array[Byte] =
      val in = Files.createTempFile(work, "in", "")
      val out = in.resolveSibling(in.getFileName.toString + ".asc")
      Files.write(in, data)
      val cmd = List(gpg) ++ home.toList.flatMap(h => List("--homedir", h.toString)) ++
        List("--batch", "--yes", "--quiet") ++
        passFile.toList.flatMap(f => List("--pinentry-mode", "loopback", "--passphrase-file", f.toString)) ++
        keyId.toList.flatMap(k => List("--local-user", k)) ++ options ++
        List("--armor", "--detach-sign", "--output", out.toString, in.toString)
      val code = runInherited(cmd)
      if code != 0 || !Files.exists(out) then fail(s"gpg failed to sign (exit $code)${if keyId.isEmpty && home.isEmpty then " -- set publish.gpgKey or publish.secretKey, or --signer none" else ""}")
      val sig = Files.readAllBytes(out)
      Files.delete(in); Files.delete(out)
      sig

    def close(): Unit =
      home.foreach { h =>
        ScalinoCli.findOnPathOpt("gpgconf").foreach(g => runCaptureStdoutQuiet(List(g, "--homedir", h.toString, "--kill", "all")))
      }
      ScalinoCli.deleteRecursively(work)

  // ---- repositories -----------------------------------------------------------------------------

  def mavenFileName(m: Meta, a: Artifact): String =
    s"${m.moduleName}-${m.version}${a.classifier.map("-" + _).getOrElse("")}.${a.ext}"

  def mavenDir(m: Meta): String = s"${m.organization.replace('.', '/')}/${m.moduleName}/${m.version}"

  /** Each file plus its `.md5`/`.sha1` (and `.asc` when signed), as (name, bytes). */
  def withChecksums(name: String, bytes: Array[Byte], signer: Option[Signer]): List[(String, Array[Byte])] =
    List(
      name -> bytes,
      s"$name.md5" -> Packaging.md5Hex(bytes).getBytes(UTF_8),
      s"$name.sha1" -> sha1Hex(bytes).getBytes(UTF_8)
    ) ++ signer.map(s => s"$name.asc" -> s.sign(bytes)).toList

  /** `maven-metadata.xml` at the artifact level, listing `versions` (sorted as given). */
  def mavenMetadata(m: Meta, versions: List[String], timestamp: String): String =
    val release = versions.filterNot(_.endsWith("SNAPSHOT")).lastOption
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<metadata>
       |  <groupId>${xml(m.organization)}</groupId>
       |  <artifactId>${xml(m.moduleName)}</artifactId>
       |  <versioning>
       |    <latest>${xml(versions.last)}</latest>
       |${release.map(r => s"    <release>${xml(r)}</release>\n").getOrElse("")}    <versions>
       |${versions.map(v => s"      <version>${xml(v)}</version>").mkString("\n")}
       |    </versions>
       |    <lastUpdated>$timestamp</lastUpdated>
       |  </versioning>
       |</metadata>
       |""".stripMargin

  private val versionTagRe = """<version>([^<]+)</version>""".r
  def mergedVersions(existing: Option[String], version: String): List[String] =
    (existing.toList.flatMap(versionTagRe.findAllMatchIn(_).map(_.group(1))) :+ version).distinct

  /** `yyyyMMddHHmmss` in UTC (no java.text / java.time in this javalib): Howard Hinnant's
   *  days-to-civil conversion. */
  def timestamp(millis: Long = System.currentTimeMillis()): String =
    val secs = millis / 1000
    val days = Math.floorDiv(secs, 86400L)
    val sod = Math.floorMod(secs, 86400L)
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if mp < 10 then mp + 3 else mp - 9
    val year = yoe + era * 400 + (if month <= 2 then 1 else 0)
    f"$year%04d$month%02d$day%02d${sod / 3600}%02d${sod / 60 % 60}%02d${sod % 60}%02d"

  private def writeFile(p: Path, bytes: Array[Byte]): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, bytes)

  /** Ivy layout (`<org>/<module>/<version>/{jars,srcs,docs,poms,ivys}/`), as sbt's publishLocal. */
  def writeIvy(root: Path, m: Meta, deps: List[Dep], artifacts: List[Artifact]): Path =
    val dir = root.resolve(m.organization).resolve(m.moduleName).resolve(m.version)
    // a republish of the same version replaces it whole (an older one may have had other artifacts)
    ScalinoCli.deleteRecursively(dir)
    for a <- artifacts do
      val sub = a.classifier match
        case Some("sources") => "srcs"
        case Some("javadoc") => "docs"
        case _ => if a.ext == "pom" then "poms" else "jars"
      val name = s"${m.moduleName}${a.classifier.map("-" + _).getOrElse("")}.${a.ext}"
      for (n, b) <- withChecksums(name, a.bytes, None) do writeFile(dir.resolve(sub).resolve(n), b)
    val ivy = ivyXml(m, deps, artifacts, timestamp()).getBytes(UTF_8)
    for (n, b) <- withChecksums("ivy.xml", ivy, None) do writeFile(dir.resolve("ivys").resolve(n), b)
    dir

  /** Maven layout under `root` (a local repository, or a `file:` publish target). */
  def writeMaven(root: Path, m: Meta, artifacts: List[Artifact], signer: Option[Signer], metadataName: String): Path =
    val dir = root.resolve(mavenDir(m))
    ScalinoCli.deleteRecursively(dir)
    for a <- artifacts; (n, b) <- withChecksums(mavenFileName(m, a), a.bytes, signer) do writeFile(dir.resolve(n), b)
    val metaPath = dir.getParent.resolve(metadataName)
    val existing = Option.when(Files.exists(metaPath))(readFile(metaPath))
    val md = mavenMetadata(m, mergedVersions(existing, m.version), timestamp()).getBytes(UTF_8)
    for (n, b) <- withChecksums(metadataName, md, None) do writeFile(dir.getParent.resolve(n), b)
    dir

  // ---- HTTP (curl) ------------------------------------------------------------------------------

  /** curl with its credentials in a `-K` config file (not on the command line, where `ps` shows
   *  them). Returns (HTTP status, body); status 0 when curl itself failed. */
  def curl(args: List[String], auth: Option[String]): (Int, String) =
    val curlBin = ScalinoCli.findOnPathOpt("curl").getOrElse(fail("publishing needs curl on the PATH"))
    val cfg = Files.createTempFile("scalino-curl", ".cfg")
    try
      try
        import java.nio.file.attribute.PosixFilePermission.*
        Files.setPosixFilePermissions(cfg, Set(OWNER_READ, OWNER_WRITE).asJava)
      catch case _: UnsupportedOperationException => ()
      Files.write(cfg, auth.map(line => s"$line\n").getOrElse("").getBytes(UTF_8))
      val marker = "\nscalino-http-status:"
      val (_, out) = runCaptureStdout(List(curlBin, "-sS", "-K", cfg.toString, "-w", s"${marker}%{http_code}") ++ args)
      val i = out.lastIndexOf(marker.trim)
      if i < 0 then (0, out)
      else (out.substring(i + marker.trim.length).trim.toIntOption.getOrElse(0), out.substring(0, i).trim)
    finally Files.deleteIfExists(cfg)

  private def curlQuote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  def basicAuth(user: String, password: String): String = s"user = ${curlQuote(s"$user:$password")}"
  def bearerAuth(user: String, password: String): String =
    val token = java.util.Base64.getEncoder.encodeToString(s"$user:$password".getBytes(UTF_8))
    s"header = ${curlQuote(s"Authorization: Bearer $token")}"

  /** Maven deploy over HTTP: one PUT per file, then the artifact-level `maven-metadata.xml`. */
  def putMaven(baseUrl: String, m: Meta, artifacts: List[Artifact], signer: Option[Signer], auth: Option[String], quiet: Boolean): Unit =
    val base = baseUrl.stripSuffix("/")
    val tmp = Files.createTempDirectory("scalino-put")
    try
      def put(rel: String, bytes: Array[Byte]): Unit =
        val f = tmp.resolve("upload")
        Files.write(f, bytes)
        val (status, body) = curl(List("-T", f.toString, s"$base/$rel"), auth)
        if status < 200 || status >= 300 then fail(s"upload of $base/$rel failed (HTTP $status)${if body.nonEmpty then s": ${body.take(500)}" else ""}")
        if !quiet then System.err.println(Color.gray(s"  uploaded $rel"))
      for a <- artifacts; (n, b) <- withChecksums(mavenFileName(m, a), a.bytes, signer) do put(s"${mavenDir(m)}/$n", b)
      val metaRel = s"${m.organization.replace('.', '/')}/${m.moduleName}/maven-metadata.xml"
      val (st, existing) = curl(List(s"$base/$metaRel"), auth)
      val md = mavenMetadata(m, mergedVersions(Option.when(st == 200)(existing), m.version), timestamp()).getBytes(UTF_8)
      for (n, b) <- withChecksums("maven-metadata.xml", md, None) do put(s"${m.organization.replace('.', '/')}/${m.moduleName}/$n", b)
    finally ScalinoCli.deleteRecursively(tmp)

  /** Overridable for tests (a local mock of the publisher API). */
  def centralBaseUrl: String =
    Option(System.getenv("SCALINO_CENTRAL_URL")).filter(_.nonEmpty).getOrElse("https://central.sonatype.com").stripSuffix("/")

  /** Sonatype Central Portal: the bundle goes up in one request, then the deployment is polled until
   *  it is validated (USER_MANAGED, `--manual-release`: publish it from the portal) or publishing
   *  (AUTOMATIC: on Maven Central within ~30 minutes), or failed, with the portal's reasons. */
  def uploadCentral(bundle: Path, m: Meta, auth: String, automatic: Boolean, quiet: Boolean): Unit =
    val base = centralBaseUrl
    val tpe = if automatic then "AUTOMATIC" else "USER_MANAGED"
    val name = java.net.URLEncoder.encode(s"${m.organization}:${m.moduleName}:${m.version}", "UTF-8")
    val (status, body) = curl(List("-X", "POST", "-F", s"bundle=@${bundle};type=application/octet-stream",
      s"$base/api/v1/publisher/upload?name=$name&publishingType=$tpe"), Some(auth))
    if status != 201 && status != 200 then
      fail(s"Central Portal upload failed (HTTP $status)${if body.nonEmpty then s": ${body.take(1000)}" else ""}" +
        (if status == 401 || status == 403 then " -- check the user token (publish.user / publish.password, default $SONATYPE_USERNAME / $SONATYPE_PASSWORD)" else ""))
    val id = body.trim
    if !quiet then System.err.println(Color.gray(s"  deployment $id uploaded, waiting for validation"))
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    val pollMs = Option(System.getenv("SCALINO_CENTRAL_POLL_MS")).flatMap(_.toIntOption).getOrElse(5000)
    var last = ""
    while true do
      val (st, json) = curl(List("-X", "POST", s"$base/api/v1/publisher/status?id=$id"), Some(auth))
      if st != 200 then fail(s"Central Portal status check failed (HTTP $st): ${json.take(500)}")
      val obj =
        try ScalinoCli.parseJson(json).asInstanceOf[Map[String, Any]]
        catch case e: Exception => fail(s"unexpected Central Portal status response: ${json.take(500)}")
      val state = obj.get("deploymentState").map(_.toString).getOrElse("?")
      if state != last && !quiet then System.err.println(Color.gray(s"  deployment $id: $state"))
      last = state
      state match
        case "FAILED" =>
          val errors = obj.get("errors").map(renderErrors).getOrElse("(no details)")
          fail(s"Central Portal rejected deployment $id:\n$errors")
        case "VALIDATED" if !automatic =>
          println(s"Validated; publish it at $base/publishing/deployments (deployment $id)")
          return
        case "PUBLISHING" | "PUBLISHED" =>
          println(s"Published ${m.organization}:${m.moduleName}:${m.version} to Maven Central (deployment $id${if state == "PUBLISHING" then "; it shows up on repo1 within ~30 minutes" else ""})")
          return
        case _ =>
          if System.currentTimeMillis() > deadline then
            fail(s"deployment $id still $state after ${timeoutSeconds}s; follow it at $base/publishing/deployments")
          Thread.sleep(pollMs)

  private def timeoutSeconds: Int =
    Option(System.getenv("SCALINO_CENTRAL_TIMEOUT")).flatMap(_.toIntOption).getOrElse(1800)

  private def renderErrors(e: Any): String = e match
    case m: Map[?, ?] => m.toList.map { case (k, v) => s"  $k:\n${renderErrors(v).linesIterator.map("  " + _).mkString("\n")}" }.mkString("\n")
    case l: List[?] => l.map(x => s"  - $x").mkString("\n")
    case other => s"  $other"

  // ---- the command ------------------------------------------------------------------------------

  def handle(args: Array[String], local: Boolean): Unit =
    val (flags, rest) = extractFlags(args)
    val o = ScalinoCli.defaultToCwd(ScalinoCli.parseRunOpts(rest))
    if o.watch then die("publish: --watch is not supported")
    o.sources.find(!Files.exists(_)).foreach(p => ScalinoCli.fileNotFound(p))
    val (main, _) = ScalinoCli.partitionSources(ScalinoCli.expandSources(o.sources))
    if main.isEmpty then die("no .scala files found")
    try publish(main, o, flags, local)
    catch case BuildFailed(msg) => exitBuildFailure(msg)

  def publish(expanded: List[Path], o: ScalinoCli.RunOpts, flags: Flags, local: Boolean): Unit =
    val build = ScalinoCli.compileForPublish(expanded, o)
    val settings = parseDirectiveSettings(build.sources).overriddenBy(flags.settings)
    val projectDir = o.sources.headOption.map(p => if Files.isDirectory(p) then p else p.toAbsolutePath.getParent).getOrElse(Paths.get("."))
    val meta = resolveMeta(settings, build.js, projectDir, local)
    val d = build.directives
    if d.jars.nonEmpty then
      System.err.println(Color.warn(s"scalino: warning: //> using jar (${d.jars.mkString(", ")}) can't be a dependency of a published library -- left out"))
    val deps =
      runtimeDeps(build.js) ++
        (d.deps ++ o.cliDeps).distinct.map(ScalinoCli.toCoursierCoord).flatMap(depOf(_, "compile")) ++
        (d.compileOnlyDeps ++ o.cliCompileOnlyDeps).distinct.map(ScalinoCli.toCoursierCoord).flatMap(depOf(_, "provided"))

    // The artifacts
    val classes = filesUnder(build.classesDir).map { case (n, p) => n -> Files.readAllBytes(p) }
    if !classes.exists(f => f._1.endsWith(".nir") || f._1.endsWith(".sjsir")) then fail("the build produced no classes to publish")
    val resources = d.resourceDirs.flatMap(r => filesUnder(Paths.get(r))).map { case (n, p) => n -> Files.readAllBytes(p) }
    val cwd = Paths.get("").toAbsolutePath.normalize
    val sourceRoot = projectDir.toAbsolutePath.normalize
    val sourceFiles = build.sources.map { p =>
      val abs = p.toAbsolutePath.normalize
      val rel =
        if abs.startsWith(sourceRoot) then sourceRoot.relativize(abs)
        else if abs.startsWith(cwd) then cwd.relativize(abs)
        else abs.getFileName
      rel.iterator().asScala.map(_.toString).mkString("/") -> Files.readAllBytes(p)
    }
    val mainClassEntry = o.mainClassOpt.orElse(d.mainClass).filterNot(_.contains(",")).map("Main-Class" -> _).toList
    val artifacts =
      List(Artifact(None, "jar", jar(classes ++ resources, mainClassEntry))) ++
        Option.when(settings.sources.getOrElse(true))(Artifact(Some("sources"), "jar", jar(sourceFiles))).toList ++
        Option.when(settings.doc.getOrElse(true))(Artifact(Some("javadoc"), "jar", jar(Nil))).toList :+
        Artifact(None, "pom", pom(meta, deps).getBytes(UTF_8))
    val coord = s"${meta.organization}:${meta.moduleName}:${meta.version}"

    if local then
      if flags.m2 then
        val root = flags.m2Home.map(Paths.get(_)).getOrElse(Paths.get(System.getProperty("user.home"), ".m2", "repository"))
        val dir = writeMaven(root, meta, artifacts, None, "maven-metadata-local.xml")
        println(s"Published $coord to ${dir.toAbsolutePath.normalize}")
      else
        val root = flags.ivy2Home.map(Paths.get(_)).getOrElse(Paths.get(System.getProperty("user.home"), ".ivy2")).resolve("local")
        val dir = writeIvy(root, meta, deps, artifacts)
        println(s"Published $coord to ${dir.toAbsolutePath.normalize}")
      if !o.quiet then
        val depForm = if meta.moduleName == meta.name + platformSuffix(build.js) then s"${meta.organization}::${meta.name}::${meta.version}" else coord
        println(s"  depend on it with: //> using dep $depForm${if flags.m2 then "  (and //> using repository m2Local)" else ""}")
      return

    // `publish`: where to
    val repo = settings.repository.getOrElse("central")
    repo match
      case "ivy2-local" | "ivy2Local" | "local" =>
        return publish(expanded, o, flags.copy(m2 = false), local = true)
      case "m2-local" | "m2Local" =>
        return publish(expanded, o, flags.copy(m2 = true), local = true)
      case _ => ()
    val isCentral = repo == "central" || repo == "sonatype" || repo.startsWith("central-") || repo.startsWith("sonatype:")
    val snapshot = meta.version.endsWith("SNAPSHOT")
    if isCentral && !snapshot then
      val missing = List(
        "publish.url" -> meta.url.isEmpty,
        "publish.license" -> meta.license.isEmpty,
        "publish.versionControl" -> meta.scm.isEmpty,
        "publish.developer" -> meta.developers.isEmpty,
        "publish.description" -> meta.description.isEmpty
      ).collect { case (k, true) => k }
      if missing.nonEmpty then fail(s"Maven Central requires ${missing.mkString(", ")} (as //> using directives or flags)")
    val signerKind = settings.signer.getOrElse(
      if isCentral || settings.gpgKey.isDefined || settings.secretKey.isDefined then "gpg" else "none")
    val secretKey = settings.secretKey.map(secretValue(_, "publish.secretKey"))
      .orElse(Option.when(signerKind == "gpg" && settings.gpgKey.isEmpty)(System.getenv("PGP_SECRET")).flatMap(Option(_)).filter(_.nonEmpty))
    val passphrase = settings.secretKeyPassword.map(secretValue(_, "publish.secretKeyPassword"))
      .orElse(Option(System.getenv("PGP_PASSPHRASE")).filter(_.nonEmpty))
    val signer = signerKind match
      case "gpg" => Some(new Signer(settings.gpgKey, secretKey, passphrase, settings.gpgOptions))
      case "none" =>
        if isCentral && !snapshot then fail("Maven Central requires signed artifacts: --signer none is not possible there")
        None
      case other => fail(s"unsupported signer '$other' (expected gpg or none; bouncycastle needs a JVM)")
    try
      def credentials(): (String, String) =
        val user = settings.user.map(secretValue(_, "publish.user")).orElse(Option(System.getenv("SONATYPE_USERNAME")).filter(_.nonEmpty))
        val pass = settings.password.map(secretValue(_, "publish.password")).orElse(Option(System.getenv("SONATYPE_PASSWORD")).filter(_.nonEmpty))
        (user, pass) match
          case (Some(u), Some(p)) => (u, p)
          case _ => fail("no credentials: set publish.user / publish.password (e.g. env:SONATYPE_USERNAME), or $SONATYPE_USERNAME / $SONATYPE_PASSWORD")
      System.err.println(Color.action(s"scalino: publishing $coord to $repo"))
      if isCentral && !snapshot then
        val bundleEntries = artifacts.flatMap(a => withChecksums(mavenFileName(meta, a), a.bytes, signer))
          .map { case (n, b) => s"${mavenDir(meta)}/$n" -> b }
        val bundle = Paths.get(".scalino-build", "publish", s"${meta.moduleName}-${meta.version}-bundle.zip")
        writeFile(bundle, zip(bundleEntries.sortBy(_._1)))
        if flags.dummy then println(s"Dummy run: wrote ${bundle.toAbsolutePath.normalize}, nothing uploaded")
        else
          val (u, p) = credentials()
          uploadCentral(bundle.toAbsolutePath, meta, bearerAuth(u, p), automatic = !flags.manualRelease, o.quiet)
      else if isCentral || repo.startsWith("http://") || repo.startsWith("https://") then
        val url = if isCentral then s"$centralBaseUrl/repository/maven-snapshots" else repo
        if flags.dummy then
          println(s"Dummy run: would upload ${artifacts.size} artifacts of $coord to $url")
        else
          val (u, p) = if isCentral || settings.user.isDefined then credentials() else ("", "")
          putMaven(url, meta, artifacts, signer, Option.when(u.nonEmpty)(basicAuth(u, p)), o.quiet)
          println(s"Published $coord to $url")
      else
        val root = if repo.startsWith("file:") then Paths.get(java.net.URI.create(repo)) else Paths.get(repo)
        val dir = writeMaven(root, meta, artifacts, signer, "maven-metadata.xml")
        println(s"Published $coord to ${dir.toAbsolutePath.normalize}")
    finally signer.foreach(_.close())
