package valistrio.core.domain

import cats.data.ValidatedNel
import cats.syntax.apply._
import cats.syntax.validated._
import io.circe.Decoder

/** A strict SemVer version with no prerelease or build metadata. */
final case class SchemaVersion(major: Int, minor: Int, patch: Int) {
  override def toString: String = s"$major.$minor.$patch"
}

object SchemaVersion {

  /** Parses a string of the form "major.minor.patch".
    *
    * Rejects prerelease suffixes (e.g. "1.0.0-rc.1"), build metadata (e.g.
    * "1.0.0+build.7"), leading zeros, non-integer or negative segments, and anything
    * other than exactly three dot-separated segments. The three segments are parsed
    * independently, so all segment faults are reported together.
    */
  def parse(s: String): ValidatedNel[String, SchemaVersion] =
    if (s.contains("-"))
      s"Schema version '$s' must not contain prerelease metadata (e.g. '-rc.1'). Use 'major.minor.patch'.".invalidNel
    else if (s.contains("+"))
      s"Schema version '$s' must not contain build metadata (e.g. '+build.7'). Use 'major.minor.patch'.".invalidNel
    else
      s.split('.') match {
        case Array(majorStr, minorStr, patchStr) =>
          (parseSegment(majorStr, "major"), parseSegment(minorStr, "minor"), parseSegment(patchStr, "patch"))
            .mapN(SchemaVersion.apply)
        case parts =>
          s"Schema version '$s' must have exactly three dot-separated segments (e.g. '1.0.0'), got ${parts.length}.".invalidNel
      }

  private def parseSegment(s: String, name: String): ValidatedNel[String, Int] =
    if (s.length > 1 && s.startsWith("0"))
      s"Schema version $name segment must not have leading zeros, got '$s'.".invalidNel
    else
      s.toIntOption match {
        case Some(n) if n >= 0 => n.validNel
        case Some(n)           => s"Schema version $name segment must be non-negative, got $n.".invalidNel
        case None              => s"Schema version $name segment must be an integer, got '$s'.".invalidNel
      }

  implicit val decoder: Decoder[SchemaVersion] =
    Decoder[String].emap(s => parse(s).toEither.left.map(_.toList.mkString("; ")))
}
