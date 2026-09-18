package valistrio.core.adapters.snowplow

import cats.syntax.either._
import valistrio.core.domain.SchemaRef

/** Translates a Snowplow iglu schema URI into a valistrio [[SchemaRef]].
  *
  * `iglu:com.acme/page_view/jsonschema/1-0-0` → `com.acme/page_view/1.0.0`: strip the `iglu:`
  * scheme, drop the `jsonschema` format segment, and render the SchemaVer `MODEL-REVISION-ADDITION`
  * as a dotted version. The three SchemaVer integers line up with valistrio's `major.minor.patch`,
  * so [[SchemaRef.parse]] stays the authority on the result — this only reshapes the string.
  */
object IgluSchema {

  private val Scheme = "iglu:"

  def toSchemaRef(uri: String): Either[String, SchemaRef] =
    if (!uri.startsWith(Scheme)) Left(s"not an iglu URI: '$uri'")
    else
      uri.stripPrefix(Scheme).split('/') match {
        case Array(group, name, "jsonschema", version) =>
          SchemaRef.parse(s"$group/$name/${version.replace('-', '.')}").toEither.leftMap(_.toList.mkString("; "))
        case _ =>
          Left(s"malformed iglu URI: '$uri' (expected iglu:group/name/jsonschema/M-R-A)")
      }
}
