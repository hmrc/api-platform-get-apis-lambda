package uk.gov.hmrc.apiplatform.getapis

import java.net.HttpURLConnection.HTTP_OK
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.{RestApi, _}

class GetApisHandlerSpec extends WordSpecLike with Matchers with MockitoSugar {

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

      val response: APIGatewayProxyResponseEvent = getApisHandler.handleInput(new APIGatewayProxyRequestEvent())

      response.getStatusCode shouldEqual HTTP_OK
      response.getBody shouldEqual """{"restApis":[{"id":"1","name":"API 1"},{"id":"2","name":"API 2"}]}"""
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

      val response: APIGatewayProxyResponseEvent = new GetApisHandler(mockAPIGatewayClient, limit = 1).handleInput(new APIGatewayProxyRequestEvent())

      response.getStatusCode shouldEqual HTTP_OK
      response.getBody shouldEqual """{"restApis":[{"id":"1","name":"API 1"},{"id":"2","name":"API 2"}]}"""
    }

    "propagate UnauthorizedException thrown by AWS SDK when retrieving APIs" in new Setup {
      val errorMessage = "You're not authorized"
      val id: String = UUID.randomUUID().toString
      when(mockAPIGatewayClient.getRestApis(any[GetRestApisRequest])).thenThrow(UnauthorizedException.builder().message(errorMessage).build())

      val exception: UnauthorizedException = intercept[UnauthorizedException](getApisHandler.handleInput(new APIGatewayProxyRequestEvent()))
      exception.getMessage shouldEqual errorMessage
    }
  }
}
