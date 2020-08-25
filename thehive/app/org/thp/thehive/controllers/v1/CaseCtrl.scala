package org.thp.thehive.controllers.v1

import javax.inject.{Inject, Named, Singleton}
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.{RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.{InputCase, InputTask}
import org.thp.thehive.models.{Permissions, RichCase, User}
import org.thp.thehive.services._
import play.api.libs.json.JsObject
import play.api.mvc.{Action, AnyContent, Results}

import scala.util.{Success, Try}

@Singleton
class CaseCtrl @Inject() (
    entrypoint: Entrypoint,
    @Named("with-thehive-schema") db: Database,
    properties: Properties,
    caseSrv: CaseSrv,
    caseTemplateSrv: CaseTemplateSrv,
    userSrv: UserSrv,
    tagSrv: TagSrv,
    organisationSrv: OrganisationSrv
) extends QueryableCtrl
    with CaseRenderer {

  override val entityName: String                           = "case"
  override val publicProperties: List[PublicProperty[_, _]] = properties.`case` ::: metaProperties[CaseSteps]
  override val initialQuery: Query =
    Query.init[CaseSteps]("listCase", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).cases)
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, CaseSteps](
    "getCase",
    FieldsParser[IdOrName],
    (param, graph, authContext) => caseSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, CaseSteps, PagedResult[(RichCase, JsObject)]](
    "page",
    FieldsParser[OutputParam], {
      case (OutputParam(from, to, extraData), caseSteps, authContext) =>
        caseSteps.richPage(from, to, extraData.contains("total")) {
          _.richCaseWithCustomRenderer(caseStatsRenderer(extraData - "total")(authContext, db, caseSteps.graph))(authContext)
        }
    }
  )
  override val outputQuery: Query = Query.outputWithContext[RichCase, CaseSteps]((caseSteps, authContext) => caseSteps.richCase(authContext))
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[CaseSteps, TaskSteps]("tasks", (caseSteps, authContext) => caseSteps.tasks(authContext)),
    Query[CaseSteps, ObservableSteps]("observables", (caseSteps, authContext) => caseSteps.observables(authContext)),
    Query[CaseSteps, UserSteps]("assignableUsers", (caseSteps, authContext) => caseSteps.assignableUsers(authContext)),
    Query[CaseSteps, OrganisationSteps]("organisations", (caseSteps, authContext) => caseSteps.organisations.visible(authContext)),
    Query[CaseSteps, AlertSteps]("alerts", (caseSteps, authContext) => caseSteps.alert.visible(authContext))
  )

  def create: Action[AnyContent] =
    entrypoint("create case")
      .extract("case", FieldsParser[InputCase])
      .extract("caseTemplate", FieldsParser[String].optional.on("caseTemplate"))
      .extract("tasks", FieldsParser[InputTask].sequence.on("tasks"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String] = request.body("caseTemplate")
        val inputCase: InputCase             = request.body("case")
        val inputTasks: Seq[InputTask]       = request.body("tasks")
        for {
          caseTemplate <- caseTemplateName.map(caseTemplateSrv.get(_).visible.richCaseTemplate.getOrFail("CaseTemplate")).flip
          customFields = inputCase.customFieldValue.map(cf => (cf.name, cf.value, cf.order))
          organisation <- userSrv.current.organisations(Permissions.manageCase).get(request.organisation).getOrFail("Organisation")
          user         <- inputCase.user.fold[Try[Option[User with Entity]]](Success(None))(u => userSrv.getOrFail(u).map(Some.apply))
          tags         <- inputCase.tags.toTry(tagSrv.getOrCreate)
          richCase <- caseSrv.create(
            caseTemplate.fold(inputCase)(inputCase.withCaseTemplate).toCase,
            user,
            organisation,
            tags.toSet,
            customFields,
            caseTemplate,
            inputTasks.map(t => t.toTask -> t.assignee.flatMap(userSrv.get(_).headOption()))
          )
        } yield Results.Created(richCase.toJson)
      }

  def get(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("get case")
      .authRoTransaction(db) { implicit request => implicit graph =>
        caseSrv
          .get(caseIdOrNumber)
          .visible
          .richCase
          .getOrFail("Case")
          .map(richCase => Results.Ok(richCase.toJson))
      }

  def update(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("update case")
      .extract("case", FieldsParser.update("case", publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("case")
        caseSrv
          .update(_.get(caseIdOrNumber).can(Permissions.manageCase), propertyUpdaters)
          .map(_ => Results.NoContent)
      }

  def delete(caseIdOrNumber: String): Action[AnyContent] =
    entrypoint("delete case")
      .authTransaction(db) { implicit request => implicit graph =>
        caseSrv
          .get(caseIdOrNumber)
          .can(Permissions.manageCase)
          .update("status" -> "deleted")
          .map(_ => Results.NoContent)
      }

  def merge(caseIdsOrNumbers: String): Action[AnyContent] =
    entrypoint("merge cases")
      .authTransaction(db) { implicit request => implicit graph =>
        caseIdsOrNumbers
          .split(',')
          .toSeq
          .toTry(
            caseSrv
              .get(_)
              .visible
              .getOrFail("Case")
          )
          .map { cases =>
            val mergedCase = caseSrv.merge(cases)
            Results.Ok(mergedCase.toJson)
          }
      }

  def list: Action[AnyContent] =
    entrypoint("list cases")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val cases = caseSrv
          .initSteps
          .visible
          .richCase
          .toList
        Success(Results.Ok(cases.toJson))
      }
}
