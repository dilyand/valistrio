package valistrio.core.resources.sinks

import cats.effect.{IO, Resource}
import fs2.kafka._
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.clients.producer.ProducerConfig
import valistrio.core.Config.KafkaConfig

import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/** Provides the shared Kafka producer both the events and DLQ [[KafkaSink]]s write through. */
object Kafka {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates the shared producer. On acquisition, probes broker connectivity so the app fails
    * fast at startup if Kafka is unreachable rather than only on the first `/post`. The probe
    * checks cluster reachability only (not that the configured topics exist) — topics are commonly
    * provisioned out-of-band, and brokers often disable auto-topic-creation.
    */
  def producer(config: KafkaConfig, requestTimeout: FiniteDuration): Resource[IO, KafkaProducer[IO, String, String]] =
    Resource.eval(checkConnectivity(config)).flatMap(_ => producerResource(config, requestTimeout))

  private def checkConnectivity(config: KafkaConfig): IO[Unit] =
    adminClient(config)
      .use(admin => IO.blocking(admin.describeCluster().nodes().get(ConnectivityCheckTimeout.toMillis, TimeUnit.MILLISECONDS)))
      .timeout(ConnectivityCheckTimeout)
      .void

  private def adminClient(config: KafkaConfig): Resource[IO, AdminClient] =
    Resource.fromAutoCloseable(IO.blocking {
      val props = new Properties()
      props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ConnectivityCheckTimeout.toMillis.toString)
      AdminClient.create(props)
    })

  private def producerResource(config: KafkaConfig, requestTimeout: FiniteDuration): Resource[IO, KafkaProducer[IO, String, String]] = {
    // Keep the producer's own deadlines under the endpoint's request timeout, so a stalled broker
    // surfaces as SinkError.Timeout (a structured 504) before the outer HTTP timeout fires.
    val deadlineMs = math.max(1000L, requestTimeout.toMillis * 4 / 5)
    // An event with no parseable event_id has no key (a malformed or truncated original), so the
    // record is written unkeyed. The default String serializer NPEs on a null key, so tolerate it.
    val nullableKey: Serializer[IO, String] =
      Serializer.instance((_, _, key) => IO.pure(Option(key).map(_.getBytes(StandardCharsets.UTF_8)).orNull))
    val settings = ProducerSettings(nullableKey, Serializer[IO, String])
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)
      .withProperties(
        ProducerConfig.MAX_BLOCK_MS_CONFIG        -> deadlineMs.toString,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG  -> deadlineMs.toString,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG -> deadlineMs.toString
      )

    KafkaProducer.resource(settings)
  }
}
