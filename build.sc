import mill._
import $ivy.`com.lihaoyi::mill-contrib-playlib:$MILL_VERSION`
import scalalib._
import playlib._

object httpclient extends RootModule with PlayApiModule// with ScalafixModule // No need for _.XXX @ tas execution
	//with PublishModule
	with ScalaModule with PlayModule {
	def scalaVersion= "2.12.18" //"2.13.6"
	val sttpVer = "3.9.7"
	def playVersion = "2.8.22"
	val jsoniterVer = "2.30.7"
	val vertxVer = "4.5.9" //3.9.16"
	val pekkoVer = "1.0.1"
	import coursier.maven.MavenRepository

	override def repositoriesTask = T.task { super.repositoriesTask() ++ Seq(
		MavenRepository("https://repo.akka.io/maven/")
	) }
/*
	override def ivyDeps = Agg(
		core(),
		server(),
		logback()
	)*/

	object test extends PlayTests {
		override def compileIvyDeps = super.compileIvyDeps() ++
			Agg(ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:$jsoniterVer"
			)

		override def ivyDeps = super.ivyDeps() ++ Agg(
			  ivy"org.mockito::mockito-scala:1.17.30" // 30
			, ivy"org.scalatest::scalatest:3.2.17"
			, ivy"org.scalatestplus.play::scalatestplus-play:5.1.0" // added by the plugin, but needed here for intelliJ
			, ivy"com.softwaremill.sttp.client3::core:$sttpVer"
			, ivy"com.softwaremill.sttp.client3::akka-http-backend:$sttpVer"
			, ivy"com.softwaremill.sttp.client3::pekko-http-backend:$sttpVer"
			, ivy"com.softwaremill.sttp.client3::zio:$sttpVer"
			, ivy"com.softwaremill.sttp.client3::armeria-backend:$sttpVer"
			, ivy"com.softwaremill.sttp.client3::okhttp-backend:$sttpVer"
			, ivy"com.squareup.okio:okio:3.0.0"
			, ivy"org.apache.pekko::pekko-actor-typed:$pekkoVer"
			, ivy"org.apache.pekko::pekko-stream:$pekkoVer"
			, ivy"org.apache.pekko::pekko-http:$pekkoVer"
			, ivy"com.typesafe.akka::akka-http:10.5.3"
			, ivy"com.typesafe.akka::akka-actor-typed:2.8.6"
			, ivy"dev.zio::zio-http:3.0.0-RC8"
			, ivy"io.vertx:vertx-web-client:$vertxVer"
			//, ivy"io.vertx::vertx-lang-scala:3.9.1"
			, ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:$jsoniterVer"
		) //++ httpclient.runIvyDeps() ++ httpclient.compileIvyDeps()  // Needed for IntelliJ import
	}
}