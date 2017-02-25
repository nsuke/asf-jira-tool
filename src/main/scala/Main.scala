package org.nsuke.jiratool

import akka.actor.ActorSystem
import akka.stream._
import scala.util.{ Success, Failure }
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutor
import akka.http.scaladsl.model.headers

import akka._
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import java.nio.file.Paths
import akka.http.scaladsl.Http

trait ThriftParams extends AsfJiraParams with GithubParams {
  // JIRA
  val projectName = "THRIFT"
  // Github
  def organization = "apache"
  def repository = "thrift"
}

sealed trait Patch
case class OutStanding(issue: String, pullRequest: Int, summary: String) extends Patch
case class Resolved(issue: String, pullRequest: Int, summary: String, resolution: String) extends Patch
case class MissingIssue(pullRequest: Int, title: String) extends Patch
case class InvalidIssue(pullRequest: Int, title: String) extends Patch
case class Unknown(pullRequest: Int, title: String) extends Patch

object Main extends App with AsfJira with Github with ThriftParams {

  println("Enter ASF JIRA username:")
  val jiraUser = scala.io.StdIn.readLine
  println("Enter ASF password:")
  val jiraSecret = new String(System.console.readPassword)
  println("Enter Github username:")
  val githubUser = scala.io.StdIn.readLine
  println("Enter Github password:")
  val githubSecret = new String(System.console.readPassword)

  val jiraAuth = headers.Authorization(headers.BasicHttpCredentials(jiraUser, jiraSecret))
  val githubAuth = headers.Authorization(headers.BasicHttpCredentials(githubUser, githubSecret))

  override implicit val system = ActorSystem("mysystem")
  override implicit val materializer = ActorMaterializer()
  override implicit val context = system.dispatcher

  lazy val extractIssueNum =
    Flow[PullRequest].map {
      case PullRequest(id, number, title) =>
        val matched = s"${projectName}[- ]([1-9][0-9]{2,3})".r.findFirstMatchIn(title.toUpperCase).map(_.group(1).toInt)
        (number, title, matched)
    }

  lazy val jiraIssue =
    Flow[(Int, String, Option[Int])].flatMapConcat {
      case (number, title, matched) => matched match {
        case Some(matchedNum) =>
          Source.single(matchedNum).via(correspondingIssue).map(issue => (number, title, Some(issue)))
        case _ =>
          Source.single(number, title, None)
      }
    }

  // These are equivalent
  // val runnable = Source.fromGraph(pullRequests).via(extractIssueNum).via(jiraIssue).via(jiraIssueType)
  val resultSource = Source.fromGraph(GraphDSL.create() { implicit b =>
    import GraphDSL.Implicits._
    val proc = pullRequests ~> extractIssueNum ~> jiraIssue ~> jiraIssueType
    SourceShape(proc.outlet)
  })

  lazy val jiraIssueType =
    Flow[(Int, String, Option[JiraIssueResponse])].map {
      case (pr, title, issue) =>
        issue match {
          case Some(JiraIssue(issueId, key, Some(Fields(summary, Some(Resolution(_, resolution)))))) =>
            // println("Resolved")
            Resolved(key, pr, summary, resolution)
          case Some(JiraIssue(issueId, key, Some(Fields(summary, None)))) =>
            // println("OutStanding")
            OutStanding(key, pr, summary)
          case Some(JiraIssueFailure(err)) =>
            println(s"Invalid: ${err}")
            InvalidIssue(pr, title)
          case None =>
            MissingIssue(pr, title)
          case _ =>
            Unknown(pr, title)
        }
    }

  def printResult(issue: Iterable[Patch]) = {
    issue foreach {
      case InvalidIssue(pr, summary) =>
        println(s"INVALID: #${pr} ${summary}")
      case Unknown(pr, summary) =>
        println(s"UNKNOWN: #${pr} ${summary}")
      case _ =>
    }
    val outstanding = issue collect { case i @ OutStanding(_, _, _) => i }
    val resolved = issue collect { case i @ Resolved(_, _, _, _) => i }
    val missing = issue collect { case i @ MissingIssue(_, _) => i }
    println("--------------------------------------------------------------------------------")
    println(s"OUTSTANDING: ${outstanding.size}")
    outstanding foreach {
      case OutStanding(issue, pr, summary) =>
        println(s"${issue} #${pr} ${summary}")
    }
    println("--------------------------------------------------------------------------------")
    println(s"Resolved: ${resolved.size}")
    resolved foreach {
      case Resolved(issue, pr, summary, res) =>
        println(s"${res} ${issue} #${pr} ${summary}")
    }
    println("--------------------------------------------------------------------------------")
    println(s"No JIRA issue name in title: ${missing.size}")
    missing foreach {
      case MissingIssue(pr, title) =>
        println(s"#${pr} ${title}")
    }
    println("--------------------------------------------------------------------------------")
  }

  println("Processing ... ")
  resultSource.runWith(Sink.seq[Patch]) andThen {
    case x =>
      x match {
        case Failure(err) => println(err)
        case Success(result) => printResult(result)
      }
      Http().shutdownAllConnectionPools() andThen { case _ => system.terminate }
  }
  Await.result(system.whenTerminated, Duration.Inf)
}
