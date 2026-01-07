package valistrio

import valistrio.TestUtils._
import valistrio.core.Config
import valistrio.core.Config.ServerConfig

import java.util.Base64
import scala.concurrent.duration.DurationInt

package object fixtures {
  val referenceConf: String =
    fromFile(
      "config/reference.conf"
    )

  val base64EncodedReferenceConf = new String(Base64.getEncoder.encode(referenceConf.getBytes))

  val parsedReferenceConf = Config(ServerConfig("0.0.0.0", 8080, 2097152L, 5.seconds))
}
