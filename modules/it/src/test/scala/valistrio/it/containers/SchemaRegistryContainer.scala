package valistrio.it.containers

import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait

/** Confluent Schema Registry container.
  *
  * Backed by [[KafkaContainer]] for storage. After [[start]], [[url]] gives
  * the base URL reachable from the test JVM, and [[internalUrl]] gives the
  * URL reachable from other containers on the same Docker network.
  */
class SchemaRegistryContainer(network: Network, kafka: KafkaContainer) extends Container {

  val Port         = 8081
  val NetworkAlias = "schema-registry"

  val container: JGenericContainer[_] = {
    val c = new JGenericContainer[Nothing]("confluentinc/cp-schema-registry:7.7.0")
    c.withNetwork(network)
    c.withNetworkAliases(NetworkAlias)
    c.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", s"PLAINTEXT://${kafka.internalBootstrap}")
    c.withEnv("SCHEMA_REGISTRY_HOST_NAME",                    NetworkAlias)
    c.withEnv("SCHEMA_REGISTRY_LISTENERS",                    s"http://0.0.0.0:$Port")
    c.withEnv("SCHEMA_REGISTRY_KAFKASTORE_SECURITY_PROTOCOL", "PLAINTEXT")
    c.waitingFor(Wait.forHttp("/subjects").forPort(Port).forStatusCode(200))
    c.withExposedPorts(Port)
    c.dependsOn(kafka.container)
    c
  }

  /** Base URL reachable from the test JVM (via the mapped host port). */
  def url: String = s"http://${container.getHost}:${container.getMappedPort(Port)}"

  /** Base URL reachable from other containers on the same Docker network. */
  def internalUrl: String = s"http://$NetworkAlias:$Port"
}
