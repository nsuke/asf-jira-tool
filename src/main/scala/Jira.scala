package org.nsuke.jiratool

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import HttpMethods._

import spray.json.DefaultJsonProtocol
import spray.json._

import akka.stream._
import scala.concurrent.{ Future, ExecutionContextExecutor }
import scala.util.{ Try, Success, Failure }

import java.io.IOException

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
// import de.heikoseeberger.akkahttpjson4s.Json4sSupport._

trait JiraJsonProtocol extends DefaultJsonProtocol {
  implicit val jqlFormat = jsonFormat4(Jql)

  implicit val resolutionFormat = jsonFormat2(Resolution)
  implicit val fieldsFormat = jsonFormat2(Fields)
  implicit val issueFormat = jsonFormat3(JiraIssue)
  implicit val responseFormat = jsonFormat2(JiraResponse)
  implicit val errorFormat = jsonFormat1(JiraError)

  //   implicit object Issue3JsonFormat extends RootJsonFormat[JiraIssue1] {
  //     def write(x: JiraIssue1) = JsNumber(42)
  //     def read(value: JsValue) : JiraIssue1 = value match {
  //       case JsObject(fields) =>
  //         fields.keys.foreach(println(_))
  //         fields("fields") match {
  //           case JsObject(fields) =>
  //             fields.keys.foreach(s => println(s"  ${s}"))
  //             fields("resolution") match {
  //               case str: JsString => JiraIssue1(str.value)
  //             }
  //           case x => deserializationError("Expected Map as JsObject, but got " + x)
  //         }
  //       case x => deserializationError("Expected Map as JsObject, but got " + x)
  //     }
  //   }
}

case class Jql(jql: String, startAt: Int, maxResults: Int, fields: List[String])
case class JiraResponse(total: Int, issues: List[JiraIssue])

case class Resolution(id: String, name: String)
case class Fields(summary: String, resolution: Option[Resolution])

sealed trait JiraIssueResponse
case class JiraIssue(id: String, key: String, fields: Option[Fields]) extends JiraIssueResponse
case class JiraIssueFailure(err: Throwable) extends JiraIssueResponse

case class JiraError(errorMessages: List[String])

trait AsfJiraParams {
  def projectName: String
  def jiraAuth: headers.Authorization
}

trait AsfJira extends JiraJsonProtocol {
  this: AsfJiraParams =>

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  implicit def context: ExecutionContextExecutor

  private lazy val jira = Http().cachedHostConnectionPoolHttps[Int]("issues.apache.org")

  private def request(issue: Int) = {
    val key = s"${projectName}-${issue}"
    HttpRequest(GET, s"/jira/rest/api/2/issue/${key}?fields=resolution,summary").withHeaders(jiraAuth) -> 0
  }

  private lazy val response: Flow[Int, Try[HttpResponse], NotUsed] =
    Flow[Int].map(request(_)).via(jira).map(_._1)

  private lazy val toJiraIssue: Flow[Try[HttpResponse], JiraIssueResponse, NotUsed] =
    Flow[Try[HttpResponse]].mapAsyncUnordered(8) {
      _.map(x => Unmarshal(x.entity).to[JiraIssue]).recover {
        case err =>
          println(s"Failed to get JIRA issue: ${err}")
          Future.successful(JiraIssueFailure(err))
      }.get
    }.recover {
      case err =>
        println(s"Failed to parse JIRA issue: ${err}")
        JiraIssueFailure(err)
    }

  lazy val correspondingIssue: Flow[Int, JiraIssueResponse, NotUsed] = response.via(toJiraIssue)

  // utilities

  def listIssues = jiraQuery(s"project=${projectName}")

  def resolvedIssues = jiraQuery(s"project=${projectName} and resolution = Fixed order by issue DESC")
  def unresolvedIssues = jiraQuery(s"project=${projectName} and resolution = Unresolved order by issue DESC")

  def jiraQuery(jql: String) = {
    val query = Marshal(Jql(jql, 0, 500, List("issue"))).to[MessageEntity]
    query flatMap {
      queryEntity =>
        val issueReq = HttpRequest(POST, uri = "/jira/rest/api/2/search", entity = queryEntity)
          .withHeaders(jiraAuth)
        val issue = Source.single(issueReq -> 0).via(jira)
        issue.runWith(Sink.head) flatMap {
          case (Success(x), _) => Unmarshal(x.entity).to[JiraResponse]
          case (Failure(err), _) => Future.failed(err)
        }
    }
  }
}
