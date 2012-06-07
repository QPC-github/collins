package controllers
package actions

import models.User

import play.api.mvc.Results
import play.api.mvc.Results.{Status => HttpStatus}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

trait RequestDataHolder {
  type This = RequestDataHolder

  val ErrorKey = "error.message"
  protected val sMap = new ConcurrentHashMap[String,String]()
  protected val _status = new AtomicReference[Option[HttpStatus]](None)

  def status(): Option[HttpStatus] = _status.get()
  def status_= (s: HttpStatus):Unit = _status.set(Some(s))

  def error(): Option[String] = string(ErrorKey)
  def string(k: String): Option[String] = Option(sMap.get(k))
  def string(k: String, default: String): String = string(k).getOrElse(default)
  def update(k: String, v: String): This = {
    sMap.put(k,v)
    this
  }
}

// This exists for staged validation, which may or may not have data to pass between stages
case class EphemeralDataHolder(message: Option[String] = None) extends RequestDataHolder {
  if (message.isDefined) {
    update(ErrorKey, message.get)
  }
}

object RequestDataHolder extends RequestDataHolder {
  private[RequestDataHolder] case class ErrorRequestDataHolder(
    message: String,
    override val status: Option[HttpStatus]
  ) extends RequestDataHolder {
    update(ErrorKey, message)
    override def toString() = message
  }

  object ErrorRequestDataHolder {
    def apply(msg: String, status: HttpStatus) = new ErrorRequestDataHolder(msg, Some(status))
    def apply(msg: String) = new ErrorRequestDataHolder(msg, Some(Results.BadRequest))
  }

  def error400(message: String): RequestDataHolder = ErrorRequestDataHolder(
    message, Results.BadRequest
  )
  def error404(message: String): RequestDataHolder = ErrorRequestDataHolder(
    message, Results.NotFound
  )
  def error409(message: String): RequestDataHolder = ErrorRequestDataHolder(
    message, Results.Conflict
  )
  def error500(message: String): RequestDataHolder = ErrorRequestDataHolder(
    message, Results.InternalServerError
  )
}

object NotImplementedError extends RequestDataHolder {
  override def toString() = "Not Implemented"
  override def status() = Some(Results.NotImplemented)
}
