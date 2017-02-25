package org.nsuke.jiratool

import scala.collection.immutable._

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import HttpMethods._

import spray.json.DefaultJsonProtocol

import scala.concurrent.{ Future, ExecutionContextExecutor }
import scala.util.{ Success, Failure, Try }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

trait GithubJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val pullRequestFormat = jsonFormat3(PullRequest)
}

case class PullRequest(id: Int, number: Int, title: String)

trait GithubParams {
  def organization: String
  def repository: String
  def githubAuth: headers.Authorization
}

trait Github extends GithubJsonProtocol {
  this: GithubParams =>

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit def context: ExecutionContextExecutor

  private lazy val github = Http().cachedHostConnectionPoolHttps[Int]("api.github.com")
  private lazy val accept = Accept(MediaRange.custom("application/vnd.github.v3+json"))

  private def pullRequestPageRequest(i: Int) =
    HttpRequest(uri = s"/repos/${organization}/${repository}/pulls?state=open&page=${i}")
      .withHeaders(accept, githubAuth)

  private def pullRequestPage: Flow[Int, Try[HttpResponse], NotUsed] =
    Flow[Int].map(pullRequestPageRequest(_) -> 0).via(github).map(_._1)

  private lazy val toPullRequests =
    Flow[Try[HttpResponse]].mapAsyncUnordered(8) {
      _.map(x => Unmarshal(x.entity).to[Seq[PullRequest]]).recover {
        case err =>
          println(s"Failed to get pull request: ${err}")
          Future.successful(Seq.empty)
      }.get
    }.recover {
      case err =>
        println(s"Failed to parse pull request: ${err}")
        Seq.empty
    }.mapConcat(x => x)

  private lazy val pageIndices =
    Flow[Try[HttpResponse]].mapConcat {
      case Success(response) =>
        val linkValues = response.header[headers.Link].get.values
        def linkParamPageNum(param: headers.LinkParam): PartialFunction[LinkValue, Int] = {
          case value if value.params.contains(param) =>
            value.uri.query().get("page").get.toInt
        }
        val next = linkValues.collectFirst(linkParamPageNum(LinkParams.next)).get
        val last = linkValues.collectFirst(linkParamPageNum(LinkParams.last)).get
        (next to last)
      case Failure(err) => Seq.empty
    }

  lazy val pullRequests = GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val bcast = b.add(Broadcast[Try[HttpResponse]](2))
    val concat = b.add(Concat[Try[HttpResponse]]())
    Source.single(0) ~> pullRequestPage ~> bcast ~> concat
    bcast ~> pageIndices ~> pullRequestPage ~> concat
    val prs = concat ~> toPullRequests
    SourceShape(prs.outlet)
  }
}
