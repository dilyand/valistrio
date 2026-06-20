package valistrio.core.domain

import io.circe.Decoder

/** A strict SemVer version with no prerelease or build metadata. */
final case class SchemaVersion(major: Int, minor: Int, patch: Int) {
  override def toString: String = s"$major.$minor.$patch"
}

object SchemaVersion {

  /** Parses a string of the form "major.minor.patch".
    *
    * Rejects:
    *  - prerelease suffixes (e.g. "1.0.0-rc.1")
    *  - build metadata (e.g. "1.0.0+build.7")
    *  - leading zeros on any segment (e.g. "01.0.0")
    *  - non-integer or negative segments
    *  - anything other than exactly three dot-separated segments
    */
  def parse(s: String): Either[String, SchemaVersion] = {
    if (s.contains("-"))
      Left(s"Schema version '$s' must not contain prerelease metadata (e.g. '-rc.1'). Use 'major.minor.patch'.")
    else if (s.contains("+"))
      Left(s"Schema version '$s' must not contain build metadata (e.g. '+build.7'). Use 'major.minor.patch'.")
    else
      s.split('.') match {
        case Array(majorStr, minorStr, patchStr) =>
          for {
            major <- parseSegment(majorStr, "major")
            minor <- parseSegment(minorStr, "minor")
            patch <- parseSegment(patchStr, "patch")
          } yield SchemaVersion(major, minor, patch)
        case parts =>
          Left(
            s"Schema version '$s' must have exactly three dot-separated segments (e.g. '1.0.0'), got ${parts.length}."
          )
      }
  }

  private def parseSegment(s: String, name: String): Either[String, Int] =
    if (s.length > 1 && s.startsWith("0"))
      Left(s"Schema version $name segment must not have leading zeros, got '$s'.")
    else
      s.toIntOption match {
        case Some(n) if n >= 0 => Right(n)
        case Some(n)           => Left(s"Schema version $name segment must be non-negative, got $n.")
        case None              => Left(s"Schema version $name segment must be an integer, got '$s'.")
      }

  implicit val decoder: Decoder[SchemaVersion] =
    Decoder[String].emap(parse)
}
