package uk.gov.hmrc.apiplatform.getapis

import java.net.HttpURLConnection.HTTP_OK

import com.amazonaws.services.lambda.runtime.events.{APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent}
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model._
import uk.gov.hmrc.api_platform_manage_api.AwsApiGatewayClient.awsApiGatewayClient
import uk.gov.hmrc.api_platform_manage_api.ErrorRecovery.recovery
import uk.gov.hmrc.aws_gateway_proxied_request_lambda.ProxiedRequestHandler

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.Try

class GetApisHandler(apiGatewayClient: ApiGatewayClient, limit: Int = 500) extends ProxiedRequestHandler {
  def this() {
    this(awsApiGatewayClient)
  }

  override def handleInput(input: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = {
    Try {
      new APIGatewayProxyResponseEvent()
        .withStatusCode(HTTP_OK)
        .withBody(toJson(GetApisResponse(getApis(Seq.empty, None))))
    } recover recovery get
  }

  @tailrec
  private def getApis(apis: Seq[Api], position: Option[String]): Seq[Api] = {
    val response: GetRestApisResponse = apiGatewayClient.getRestApis(buildRequest(position))
    val moreApis: Seq[Api] = response.items().asScala.map(item => Api(item.id(), item.name()))
    if (moreApis.size < limit) {
      apis ++ moreApis
    } else {
      getApis(apis ++ moreApis, Some(response.position()))
    }
  }

  private def buildRequest(position: Option[String]): GetRestApisRequest = {
    position match {
      case Some(p) => GetRestApisRequest.builder().limit(limit).position(p).build()
      case None => GetRestApisRequest.builder().limit(limit).build()
    }
  }
}

case class GetApisResponse(restApis: Seq[Api])
case class Api(id: String, name: String)
