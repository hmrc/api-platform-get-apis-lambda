package uk.gov.hmrc.apiplatform.getapis

import java.net.HttpURLConnection.HTTP_OK
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest._
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.{RestApi, _}
import software.amazon.awssdk.utils.StringInputStream
import io.github.mkotsur.aws.handler.CanDecode
import io.circe.Json
import io.circe.generic.auto._, io.circe.syntax._
import io.circe.parser._
import io.circe.Encoder
import io.circe.Decoder
import io.github.mkotsur.aws.handler._
import java.io._
import scala.util.Using
import java.nio.charset.StandardCharsets
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import scala.util.Try
import com.amazonaws.services.lambda.runtime.Context
import io.github.mkotsur.aws.proxy.ApiProxyRequest
import io.github.mkotsur.aws.proxy.RequestContext

import io.github.mkotsur.aws.proxy._
import uk.gov.hmrc.apiplatform.getapis.GetApisHandler.Api
import uk.gov.hmrc.apiplatform.getapis.GetApisHandler.GetApisResponse

object GetApisHandlerSpec {
  def makeCall[O: Decoder](handler: RequestStreamHandler)(context: Context): Either[io.circe.Error, ApiProxyResponse[O]] = {
    val res = Using.Manager { use =>
      val apiProxyRequest = ApiProxyRequest[Unit, RequestContext](path = "/", httpMethod = "GET", requestContext = RequestContext(None), body = None)
      val requestAsJson = apiProxyRequest.asJson.toString()
    
      val is = use(new StringInputStream(requestAsJson))
      val os = use(new ByteArrayOutputStream())

      handler.handleRequest(is, os, context)

      print("About to decode")
      decode[ApiProxyResponse[String]](os.toString())
    }
    // println(res)
    res.get.map( r => {
      val o: Option[O] = r.body.fold[Option[O]](None)(rb => decode[O](rb).toOption)
      ApiProxyResponse(r.statusCode, r.headers, o)
    })
  }
}

class GetApisHandlerSpec extends AnyWordSpec with Matchers with MockitoSugar with EitherValues with OptionValues with Inside {

  trait Setup {
    val mockAPIGatewayClient: ApiGatewayClient = mock[ApiGatewayClient]
    val getApisHandler = new GetApisHandler(mockAPIGatewayClient)
  }
  
  "Get APIs Handler" should {
    "retrieve the APIs from the API Gateway" in new Setup {
      val apiGatewayResponse: GetRestApisResponse = GetRestApisResponse.builder().items(
        RestApi.builder().id("1").name("API 1").build(),
        RestApi.builder().id("2").name("API 2").build()
      ).build()

      when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenReturn(apiGatewayResponse)

      val response = GetApisHandlerSpec.makeCall[GetApisResponse](getApisHandler)(mock[Context])
      
      inside(response.value) {
        case ApiProxyResponse(status, _, ob) =>
          status shouldBe HTTP_OK
          ob.value shouldBe GetApisResponse(
            List(
              Api("1", "API 1"),
              Api("2", "API 2")
            )
          )
      }
    }

    "retrieve the APIs in multiple requests if the number of APIs exceeds the limit per request" in new Setup {
      val apiGatewayFirstResponse: GetRestApisResponse = GetRestApisResponse.builder().items(
        RestApi.builder().id("1").name("API 1").build()
      ).position(UUID.randomUUID().toString).build()

      val apiGatewaySecondResponse: GetRestApisResponse = GetRestApisResponse.builder().items(
        RestApi.builder().id("2").name("API 2").build()
      ).build()
      
      when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest]))
        .thenReturn(apiGatewayFirstResponse, apiGatewaySecondResponse)

      val limitedHandler = new GetApisHandler(mockAPIGatewayClient, limit = 1)
      val response = GetApisHandlerSpec.makeCall[GetApisResponse](limitedHandler)(mock[Context])

      inside(response.value) {
        case ApiProxyResponse(status, _, ob) =>
          status shouldEqual HTTP_OK
          // ob.get shouldEqual """{"restApis":[{"id":"1","name":"API 1"},{"id":"2","name":"API 2"}]}"""
          ob.value shouldBe GetApisResponse(
            List(
              Api("1", "API 1"),
              Api("2", "API 2")
            )
          )
      }
    }

    "propagate UnauthorizedException thrown by AWS SDK when retrieving APIs" in new Setup {
      val errorMessage = "You're not authorized"
      val id: String = UUID.randomUUID().toString
      when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val response = GetApisHandlerSpec.makeCall[GetApisResponse](getApisHandler)(mock[Context])

      inside(response.value) {
        case ApiProxyResponse(status, _, ob) => 
          status shouldBe 500
          // ob.value shouldBe errorMessage
      }
    }
  }
}
