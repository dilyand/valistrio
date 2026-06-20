package valistrio.it.containers

import org.testcontainers.containers.{GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait

/** Confluent Kafka container running in KRaft mode (no ZooKeeper required).
  *
  * Exposes one listener:
  *  - PLAINTEXT on [[BrokerPort]] — used by Schema Registry inside the Docker
  *    network via the `kafka` alias, and mapped to a random host port for
  *    direct client use if needed.
  *
  * A deterministic [[ClusterId]] is hardcoded so tests are reproducible.
  * Generate a fresh one with: `kafka-storage.sh random-uuid`
  */
class KafkaContainer(network: Network) extends Container {

  val BrokerPort     = 9092
  val ControllerPort = 9093
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
      s"PLAINTEXT://0.0.0.0:$BrokerPort,CONTROLLER://0.0.0.0:$ControllerPort")
    c.withEnv("KAFKA_ADVERTISED_LISTENERS",             s"PLAINTEXT://$NetworkAlias:$BrokerPort")
    c.withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",   "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
    c.withEnv("KAFKA_INTER_BROKER_LISTENER_NAME",       "PLAINTEXT")
    c.withEnv("KAFKA_CONTROLLER_LISTENER_NAMES",        "CONTROLLER")
    c.withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
    c.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE",        "false")
    c.withEnv("CLUSTER_ID",                             ClusterId)
    c.waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
    c.withExposedPorts(BrokerPort)
    c
  }

  /** Broker address reachable from inside the Docker network. */
  def internalBootstrap: String = s"$NetworkAlias:$BrokerPort"
}
