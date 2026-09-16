package valistrio.it.containers

import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import valistrio.BuildInfo

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Containerised Valistrio app, built from the locally published Docker image.
  *
  * Configuration is injected via [[valistrio.core.Config.ValistrioConfigVar]] as a
  * base64-encoded HOCON blob, following the same mechanism as production deployments.
  *
  * @param schemaRegistryInternalUrl SR URL reachable from inside the Docker network,
  *                                   e.g. `http://schema-registry:8081`
  * @param kafkaInternalBootstrap    Kafka bootstrap address reachable from inside the
  *                                   Docker network, e.g. `kafka:9092` — KafkaSink probes
  *                                   connectivity at startup, so this must be reachable
  *                                   or the container never becomes healthy.
  */
class ValistrioContainer(network: Network, schemaRegistryInternalUrl: String, kafkaInternalBootstrap: String)
    extends Container {

  val Port = 8080

  val container: JGenericContainer[_] = {
    val image = s"${BuildInfo.dockerImageName}:${BuildInfo.dockerImageTag}"
    val c     = new JGenericContainer[Nothing](image)
    c.withNetwork(network)
    c.withNetworkAliases("valistrio")
    c.withEnv(valistrio.core.Config.ValistrioConfigVar, configBlob(schemaRegistryInternalUrl, kafkaInternalBootstrap))
    c.waitingFor(Wait.forHttp("/health").forPort(Port).forStatusCode(200))
    c.withExposedPorts(Port)
    c
  }

  /** Base URL reachable from the test JVM (via the mapped host port). */
  def url: String = s"http://${container.getHost}:${container.getMappedPort(Port)}"

  // ---- Private ----

  /** Produces the base64-encoded HOCON that overrides the SR URL and Kafka bootstrap. */
  private def configBlob(srUrl: String, kafkaBootstrap: String): String = {
    val hocon =
      s"""valistrio {
         |  schemaRegistry { url = "$srUrl" }
         |  kafka { bootstrapServers = "$kafkaBootstrap" }
         |}""".stripMargin
    Base64.getEncoder.encodeToString(hocon.getBytes(StandardCharsets.UTF_8))
  }
}
