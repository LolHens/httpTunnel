name := (name in ThisBuild).value

inThisBuild(Seq(
  name := "httpTunnel",
  organization := "org.lolhens",
  version := "0.1.0",

  scalaVersion := "2.12.4",

  resolvers ++= Seq(
    "artifactory-maven" at "http://lolhens.no-ip.org/artifactory/maven-public/",
    Resolver.url("artifactory-ivy", url("http://lolhens.no-ip.org/artifactory/ivy-public/"))(Resolver.ivyStylePatterns)
  ),

  scalacOptions ++= Seq("-Xmax-classfile-name", "127")
))

lazy val root = project.in(file("."))
  .settings(publishArtifact := false)
  .aggregate(
    httpTunnel
  )

lazy val httpTunnel = project
  .settings(name := (name in ThisBuild).value)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % "10.0.10",
      "org.scodec" %% "scodec-bits" % "1.1.5",
      "org.scodec" %% "scodec-akka" % "0.3.0",
      "org.http4s" %% "http4s-dsl" % "0.18.4",
      "org.http4s" %% "http4s-blaze-server" % "0.18.4",
      "io.monix" %% "monix" % "3.0.0-RC1",
      "org.lz4" % "lz4-java" % "1.4.0"
    ),

    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.0"),

    mainClass in Compile := Some("org.lolhens.tunnel.TunnelClient")
  )
