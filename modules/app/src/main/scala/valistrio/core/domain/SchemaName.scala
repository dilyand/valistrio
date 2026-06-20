package valistrio.core.domain

import io.circe.Decoder

/** A fully qualified schema name of the form "group/name/version",
  * e.g. "com.myorg/page_view/1.0.0".
  */
final case class SchemaName(group: String, name: String, version: SchemaVersion) {
  override def toString: String = s"$group/$name/$version"
}

object SchemaName {

  private val GroupPattern = "[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*".r
  private val NamePattern  = "[a-z][a-z0-9_]*".r

  /** Parses a string of the form "group/name/major.minor.patch".
    *
    * Rules:
    *  - exactly two '/' separators
    *  - group: reverse-domain style, lowercase letters and digits, dot-separated,
    *    no empty segments (e.g. "com.myorg")
    *  - name: lowercase snake_case (e.g. "page_view")
    *  - version: delegated to [[SchemaVersion.parse]]
    */
  def parse(s: String): Either[String, SchemaName] =
    s.split('/') match {
      case Array(group, name, version) =>
        for {
          _ <- validateGroup(group, s)
          _ <- validateName(name, s)
          v <- SchemaVersion.parse(version).left.map(err => s"In schema '$s': $err")
        } yield SchemaName(group, name, v)
      case parts =>
        Left(
          s"Schema name '$s' must have the form 'group/name/version' (exactly two '/' separators), got ${parts.length - 1}."
        )
    }

  private def validateGroup(group: String, full: String): Either[String, Unit] =
    if (GroupPattern.pattern.matcher(group).matches())
      Right(())
    else
      Left(
        s"In schema '$full': group '$group' must be reverse-domain style with lowercase letters and digits (e.g. 'com.myorg')."
      )

  private def validateName(name: String, full: String): Either[String, Unit] =
    if (NamePattern.pattern.matcher(name).matches())
      Right(())
    else
      Left(
        s"In schema '$full': name '$name' must be lowercase snake_case (e.g. 'page_view')."
      )

  implicit val decoder: Decoder[SchemaName] =
    Decoder[String].emap(parse)
}
