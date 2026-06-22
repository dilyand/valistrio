package valistrio.it.containers

import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait

import java.net.ServerSocket

/** Confluent Kafka container running in KRaft mode (no ZooKeeper required).
  *
  * Exposes two listeners:
  *  - PLAINTEXT on [[BrokerPort]] — used by Schema Registry and other containers
  *    inside the Docker network via the `kafka` alias.
  *  - EXTERNAL on a fixed host port, advertised as `localhost:$externalPort` —
  *    used by test code running directly on the host JVM (e.g. a [[valistrio.core.post.KafkaSink]]
  *    under test), which can't resolve the `kafka` network alias.
  *
  * A deterministic [[ClusterId]] is hardcoded so tests are reproducible.
  * Generate a fresh one with: `kafka-storage.sh random-uuid`
  */
class KafkaContainer(network: Network) extends Container {

  val BrokerPort     = 9092
  val ControllerPort = 9093
  val ExternalPort   = findFreePort()
  val NetworkAlias   = "kafka"

  // Deterministic URL-safe base64 UUID (22 chars, no padding) — valid KRaft cluster ID
  private val ClusterId = "ciWo7IWazngRchmygF1Lbg"

  val container: JGenericContainer[_] = {
    val c = new JGenericContainer[Nothing]("confluentinc/cp-kafka:7.7.0")
    c.withNetwork(network)
    c.withNetworkAliases(NetworkAlias)
    // KRaft — single node acting as both broker and controller
    c.withEnv("KAFKA_NODE_ID",                          "1")
    c.withEnv("KAFKA_PROCESS_ROLES",                    "broker,controller")
    c.withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS",         s"1@$NetworkAlias:$ControllerPort")
    c.withEnv("KAFKA_LISTENERS",
      s"PLAINTEXT://0.0.0.0:$BrokerPort,CONTROLLER://0.0.0.0:$ControllerPort,EXTERNAL://0.0.0.0:$ExternalPort")
    c.withEnv("KAFKA_ADVERTISED_LISTENERS",
      s"PLAINTEXT://$NetworkAlias:$BrokerPort,EXTERNAL://localhost:$ExternalPort")
    c.withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
      "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT")
    c.withEnv("KAFKA_INTER_BROKER_LISTENER_NAME",       "PLAINTEXT")
    c.withEnv("KAFKA_CONTROLLER_LISTENER_NAMES",        "CONTROLLER")
    c.withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
    c.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE",        "false")
    c.withEnv("CLUSTER_ID",                             ClusterId)
    c.waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
    c.withExposedPorts(BrokerPort)
    c.setPortBindings(java.util.List.of(s"$ExternalPort:$ExternalPort"))
    c
  }

  /** Broker address reachable from inside the Docker network. */
  def internalBootstrap: String = s"$NetworkAlias:$BrokerPort"

  /** Broker address reachable from the host JVM running the test process. */
  def externalBootstrap: String = s"localhost:$ExternalPort"

  private def findFreePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }
}
