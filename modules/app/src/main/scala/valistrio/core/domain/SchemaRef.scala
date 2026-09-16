package valistrio.core.domain

import cats.data.ValidatedNel
import cats.syntax.apply._
import cats.syntax.validated._
import io.circe.{Decoder, Encoder}

/** A fully qualified schema reference of the form "group/name/version",
  * e.g. "com.myorg/page_view/1.0.0".
  */
final case class SchemaRef(group: String, name: String, version: SchemaVersion) {
  override def toString: String = s"$group/$name/$version"
}

object SchemaRef {

  private val GroupPattern = "[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*".r
  private val NamePattern  = "[a-z][a-z0-9_]*".r

  /** Parses a string of the form "group/name/major.minor.patch".
    *
    * Rules:
    *  - exactly two '/' separators
    *  - group: reverse-domain style, lowercase letters and digits, dot-separated
    *  - name: lowercase snake_case (e.g. "page_view")
    *  - version: delegated to [[SchemaVersion.parse]]
    *
    * Once the three parts are present, group, name and version are parsed
    * independently so all faults are reported together.
    */
  def parse(s: String): ValidatedNel[String, SchemaRef] =
    s.split('/') match {
      case Array(group, name, version) =>
        (parseGroup(group, s), parseName(name, s), parseVersion(version, s)).mapN(SchemaRef.apply)
      case parts =>
        s"Schema name '$s' must have the form 'group/name/version' (exactly two '/' separators), got ${parts.length - 1}.".invalidNel
    }

  private def parseGroup(group: String, full: String): ValidatedNel[String, String] =
    if (GroupPattern.pattern.matcher(group).matches()) group.validNel
    else
      s"In schema '$full': group '$group' must be reverse-domain style with lowercase letters and digits (e.g. 'com.myorg').".invalidNel

  private def parseName(name: String, full: String): ValidatedNel[String, String] =
    if (NamePattern.pattern.matcher(name).matches()) name.validNel
    else
      s"In schema '$full': name '$name' must be lowercase snake_case (e.g. 'page_view').".invalidNel

  private def parseVersion(version: String, full: String): ValidatedNel[String, SchemaVersion] =
    SchemaVersion.parse(version).leftMap(_.map(err => s"In schema '$full': $err"))

  implicit val decoder: Decoder[SchemaRef] =
    Decoder[String].emap(s => parse(s).toEither.left.map(_.toList.mkString("; ")))

  implicit val encoder: Encoder[SchemaRef] =
    Encoder[String].contramap(_.toString)
}
