package controllers

import actions.asset.{CreateAction, FindSimilarAction}
import actions.resources.{FindAction, IntakeStage1Action, IntakeStage2Action, IntakeStage3Action}

import models._
import views._

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._

trait Resources extends Controller {
  this: SecureController =>

  def index = SecureAction { implicit req =>
    Ok(html.resources.index(AssetMeta.getViewable()))
  }(Permissions.Resources.Index)

  def displayCreateForm(assetType: String) = SecureAction { implicit req =>
    val atype: Option[AssetType.Enum] = try {
      Some(AssetType.Enum.withName(assetType))
    } catch {
      case _ => None
    }
    atype match {
      case Some(t) => t match {
        case AssetType.Enum.ServerNode =>
          Redirect(app.routes.Resources.index).flashing("error" -> "Server Node not supported for creation")
        case _ =>
          Ok(html.resources.create(t))
      }
      case None =>
        Redirect(app.routes.Resources.index).flashing("error" -> "Invalid asset type specified")
    }
  }(Permissions.Resources.CreateForm)

  def createAsset(atype: String) = CreateAction(
    None, Some(atype), Permissions.Resources.CreateAsset, this
  )

  /**
   * Find assets by query parameters, special care for ASSET_TAG
   */
  def find(page: Int, size: Int, sort: String, operation: String) = FindAction(
    PageParams(page, size, sort), operation, Permissions.Resources.Find, this
  )

  def similar(tag: String, page: Int, size: Int, sortType: String, sort: String) = 
    FindSimilarAction(tag, PageParams(page, size, sort), Permissions.Resources.Find, this)

  def intake(id: Long, stage: Int = 1) = stage match {
    case 2 => IntakeStage2Action(id, Permissions.Resources.Intake, this)
    case 3 => IntakeStage3Action(id, Permissions.Resources.Intake, this)
    case n => IntakeStage1Action(id, Permissions.Resources.Intake, this)
  }

  /**
   * Manage 4 stage asset intake process
   */
  /*
  def intake(id: Long, stage: Int = 1) = SecureAction { implicit req =>
    Redirect(app.routes.Resources.index).flashing(
      "success" -> "Successfull intake of %s".format(asset.tag)
    )
  }(Permissions.Resources.Intake)
  */

}
