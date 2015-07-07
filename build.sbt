import play.Project._

name := """SimSCD"""

version := "2.0"

libraryDependencies ++= Seq(
  "org.webjars" %% "webjars-play" % "2.2.0", 
  "org.webjars" % "bootstrap" % "2.3.1",
  "com.typesafe.akka" %% "akka-actor" % "2.2.3",
  "com.typesafe.akka" %% "akka-remote" % "2.2.3"
)

playScalaSettings

scalacOptions += "-feature"


//sources in (Compile,doc) := Seq.empty
//
//publishArtifact in (Compile, packageDoc) := false

sources in (Compile, doc) ~= (_ filterNot (_.getPath contains "managed"))

autoAPIMappings := true

apiMappings ++= {
  val cp: Seq[Attributed[File]] = (fullClasspath in Compile).value
  def findManagedDependency(organization: String, name: String): File = {
    ( for {
      entry <- cp
      module <- entry.get(moduleID.key)
      if module.organization == organization
      if module.name.startsWith(name)
      jarFile = entry.data
    } yield jarFile
      ).head
  }
  Map(
    findManagedDependency("com.typesafe.akka", "akka-actor") -> url("http://doc.akka.io/api/akka/2.2.3/"),
    findManagedDependency("com.typesafe.play", "play") -> url("https://www.playframework.com/documentation/2.2.1/api/scala/index.html"),
    findManagedDependency("com.typesafe.play", "play-json") -> url("https://www.playframework.com/documentation/2.2.1/api/scala/index.html"),
    findManagedDependency("org.scala-lang", "scala-library") -> url("http://www.scala-lang.org/api/2.10.4/")
  )
}
