logLevel := Level.Warn

resolvers ++= Seq(
  "artifactory-maven" at "http://lolhens.no-ip.org/artifactory/maven-public/",
  Resolver.url("artifactory-ivy", url("http://lolhens.no-ip.org/artifactory/ivy-public/"))(Resolver.ivyStylePatterns)
)

//addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC9")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.3")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "5.2.3")

addSbtPlugin("org.lolhens" % "sbt-assembly-minifier" % "0.5.5")

//addSbtPlugin("com.lucidchart" % "sbt-cross" % "3.0")

//addSbtPlugin("org.scala-native" % "sbt-crossproject" % "0.2.2")

//addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.0.0-M1")

//addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.3.1")
