/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package play.filters.csrf

import play.api.libs.ws.WS.WSRequestHolder
import scala.concurrent.Future
import play.api.libs.ws._
import play.api.mvc._
import play.api.libs.json.Json
import play.api.test.{FakeApplication, TestServer}
import scala.util.Random

/**
 * Specs for the global CSRF filter
 */
object CSRFFilterSpec extends CSRFCommonSpecs {

  import CSRFConf._

  "a CSRF filter also" should {

    // conditions for adding a token
    "not add a token to non GET requests" in {
      csrfAddToken(_.put(""))(_.status must_== NOT_FOUND)
    }
    "not add a token to GET requests that don't accept HTML" in {
      csrfAddToken(_.withHeaders(ACCEPT -> "application/json").get())(_.status must_== NOT_FOUND)
    }
    "add a token to GET requests that accept HTML" in {
      csrfAddToken(_.withHeaders(ACCEPT -> "text/html").get())(_.status must_== OK)
    }

    // extra conditions for not doing a check
    "not check non form bodies" in {
      csrfCheckRequest(_.post(Json.obj("foo" -> "bar")))(_.status must_== OK)
    }
    "not check safe methods" in {
      csrfCheckRequest(_.put(Map("foo" -> "bar")))(_.status must_== OK)
    }

    // other
    "feed the body once a check has been done and passes" in {
      withServer {
        case _ => CSRFFilter()(Action(
          _.body.asFormUrlEncoded
            .flatMap(_.get("foo"))
            .flatMap(_.headOption)
            .map(Results.Ok(_))
            .getOrElse(Results.NotFound)))
      } {
        val token = generate
        await(WS.url("http://localhost:" + testServerPort).withSession(TokenName -> token)
          .post(Map("foo" -> "bar", TokenName -> token))).body must_== "bar"
      }
    }
    "feed a not fully buffered body once a check has been done and passes" in running(TestServer(testServerPort, FakeApplication(
      additionalConfiguration = Map("application.secret" -> "foobar", "csrf.body.bufferSize" -> "200"),
      withRoutes = {
        case _ => CSRFFilter()(Action(
          _.body.asFormUrlEncoded
            .flatMap(_.get("foo"))
            .flatMap(_.headOption)
            .map(Results.Ok(_))
            .getOrElse(Results.NotFound)))
      }
    ))) {
      val token = generate
      val response = await(WS.url("http://localhost:" + testServerPort).withSession(TokenName -> token)
        .withHeaders(CONTENT_TYPE -> "application/x-www-form-urlencoded")
        .post(
          Seq(
            // Ensure token is first so that it makes it into the buffered part
            TokenName -> token,
            // This value must go over the edge of csrf.body.bufferSize
            "longvalue" -> Random.alphanumeric.take(1024).mkString(""),
            "foo" -> "bar"
          ).map(f => f._1 + "=" + f._2).mkString("&")
        )
      )
      response.status must_== OK
      response.body must_== "bar"
    }
  }

  def csrfCheckRequest[T](makeRequest: (WSRequestHolder) => Future[Response])(handleResponse: Response => T) = {
    withServer {
      case _ => CSRFFilter()(Action(Results.Ok))
    } {
      handleResponse(await(makeRequest(WS.url("http://localhost:" + testServerPort))))
    }
  }

  def csrfAddToken[T](makeRequest: (WSRequestHolder) => Future[Response])(handleResponse: Response => T) = {
    withServer {
      case _ => CSRFFilter()(Action { implicit req =>
        CSRF.getToken(req).map { token =>
          Results.Ok(token.value)
        } getOrElse Results.NotFound
      })
    } {
      handleResponse(await(makeRequest(WS.url("http://localhost:" + testServerPort))))
    }
  }
}
