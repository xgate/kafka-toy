name := "kafka-toy"

version := "1.0"

scalaVersion := "2.11.7"

libraryDependencies ++= {
  val akkaVersion = "2.3.12"
  Seq(
    // testing
    "org.scalatest" %% "scalatest" % "2.2.4" % "test",
    // logging
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    // akka
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-kernel" % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-contrib" % akkaVersion,
    // kafka
    "org.apache.kafka" % "kafka_2.11" % "0.8.2.1",
    // etc
    "org.apache.commons" % "commons-lang3" % "3.3.2"
  )
}
