name := (name in ThisBuild).value

inThisBuild(Seq(
  name := "httpTunnel",
  organization := "org.lolhens",
  version := "0.0.0",

  scalaVersion := "2.12.3",

  externalResolvers := Seq(
    "artifactory-maven" at "http://lolhens.no-ip.org/artifactory/maven-public/",
    Resolver.url("artifactory-ivy", url("http://lolhens.no-ip.org/artifactory/ivy-public/"))(Resolver.ivyStylePatterns)
  ),

  scalacOptions ++= Seq("-Xmax-classfile-name", "254"),

  publishTo := Some(Resolver.file("file", new File("target/releases")))
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
      "com.typesafe.akka" %% "akka-http" % "10.0.9",
      "io.monix" %% "monix" % "2.3.0"
    ),

    mainClass in Compile := Some("org.lolhens.tunnel.TunnelClient")
  )
