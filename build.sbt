scalaVersion := "2.11.8"

lazy val root = (project in file("."))

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.17",
  "com.typesafe.akka" %% "akka-stream" % "2.4.17",
  "com.typesafe.akka" %% "akka-http-core" % "2.4.11.1",
  "com.typesafe.akka" %% "akka-http-experimental" % "2.4.11.1",
  "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "2.4.11.1",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0",
  "ch.qos.logback" % "logback-classic" % "1.1.7")

cancelable in Global := true
