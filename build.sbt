inThisBuild(Seq(
  name := "httpTunnel",
  organization := "org.lolhens",
  version := "0.1.3",

  scalaVersion := "2.12.6",

  resolvers ++= Seq(
    "lolhens-maven" at "http://artifactory.lolhens.de/artifactory/maven-public/",
    Resolver.url("lolhens-ivy", url("http://artifactory.lolhens.de/artifactory/ivy-public/"))(Resolver.ivyStylePatterns)
  ),

  scalacOptions ++= Seq("-Xmax-classfile-name", "127")
))

name := (ThisBuild / name).value

lazy val root = project.in(file("."))
  .settings(publishArtifact := false)
  .aggregate(
    httpTunnel
  )

lazy val httpTunnel = project
  .settings(name := (ThisBuild / name).value)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % "10.1.5",
      "com.typesafe.akka" %% "akka-stream" % "2.5.16",
      "org.scodec" %% "scodec-bits" % "1.1.6",
      "org.scodec" %% "scodec-akka" % "0.3.0",
      "org.http4s" %% "http4s-dsl" % "0.18.17",
      "org.http4s" %% "http4s-blaze-server" % "0.18.17",
      "io.monix" %% "monix" % "3.0.0-RC1",
      "org.lz4" % "lz4-java" % "1.4.1"
    ),

    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"),

    Compile / mainClass := Some("org.lolhens.tunnel.WebsocketTunnelClient"),

    assembly / assemblyOption := (assembly / assemblyOption).value
      .copy(prependShellScript = Some(AssemblyPlugin.defaultUniversalScript(shebang = false))),

    assembly / assemblyJarName := s"TunnelClient-${version.value}.sh.bat",

    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
