package consumer.simple

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.duration._

import scala.concurrent.duration.Duration

object SimpleEventConsumerRunner extends App {

  val system = ActorSystem("simple-consumer-world")

  val eventConsumer = system.actorOf(SimpleEventConsumer.props, "simple-event-consumer")

  import system.dispatcher
  implicit val timeout: Timeout = Timeout(10.seconds)

  system.scheduler.schedule(Duration.Zero, Duration.create(3, TimeUnit.SECONDS)) {
    val fetchSize = 10
    val results = (eventConsumer ? SimpleEventConsumer.Fetch(fetchSize)).mapTo[SimpleEventConsumer.FetchResult]
    results.foreach {
      r =>
        println("size = " + r.size)
        r.messages.foreach {
          m =>
            println("message: " + m)
        }
    }
  }
}

