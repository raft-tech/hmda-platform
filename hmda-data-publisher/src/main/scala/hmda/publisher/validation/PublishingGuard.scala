package hmda.publisher.validation

import akka.actor.ActorSystem
import cats.data.{Validated, ValidatedNel}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import hmda.publisher.query.component.{PublisherComponent2018, PublisherComponent2019, PublisherComponent2020, PublisherComponent2021}
import hmda.publisher.util.MattermostNotifier
import hmda.publisher.validation.PublishingGuard.{Period, Scope}
import hmda.query.DbConfiguration
import slick.basic.DatabaseConfig
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}
// $COVERAGE-OFF$
class PublishingGuard(
                       db2018: PublisherComponent2018,
                       db2019: PublisherComponent2019,
                       db2020: PublisherComponent2020,
                       db2021: PublisherComponent2021,
                       messageReporter: MattermostNotifier,
                       dbConfig: DatabaseConfig[JdbcProfile]
                     )(
                       implicit ec: ExecutionContext
                     ) extends LazyLogging {

  def runIfDataIsValid(year: Period, scope: Scope)(thunk: => Unit): Future[Unit] =
    validate(getChecks(year, scope))
      .flatMap({
        case Validated.Valid(_) =>
          logger.debug(s"Data validation successful")
          Future(thunk)
        case Validated.Invalid(errs) =>
          val message = errs.toList.mkString("\n")
          logger.error(s"Data validation failed for year ${year}. Files won't be published. Message:\n${message}")
          messageReporter.report(message)
      })
      .recoverWith {
        case ex =>
          logger.error(s"Data validation failed with unexpected exception", ex)
          messageReporter.report(s"Data validation failed with unexpected exception: $ex")
      }

  private def getChecks(year: Period, scope: Scope): List[ValidationCheck] = {
    val leiCheckErrorMargin = year match {
      case Period.y2018   => 5
      case Period.y2019   => 1
      case Period.y2020   => 0
      case Period.y2020Q1 => 0
      case Period.y2020Q2 => 0
      case Period.y2020Q3 => 0
      case Period.y2021Q1 => 0
      case Period.y2021Q2 => 0
      case Period.y2021Q3 => 0
    }

    scope match {
      case Scope.Private =>
        val larData = year match {
          case Period.y2018   => db2018.validationLarData2018
          case Period.y2019   => db2019.validationLarData2019
          case Period.y2020   => db2020.validationLarData2020(db2020.Year2020Period.Whole)
          case Period.y2020Q1 => db2020.validationLarData2020(db2020.Year2020Period.Q1)
          case Period.y2020Q2 => db2020.validationLarData2020(db2020.Year2020Period.Q2)
          case Period.y2020Q3 => db2020.validationLarData2020(db2020.Year2020Period.Q3)
          case Period.y2021Q1 => db2021.validationLarData2021(db2021.Year2021Period.Q1)
          case Period.y2021Q2 => db2021.validationLarData2021(db2021.Year2021Period.Q2)
          case Period.y2021Q3 => db2021.validationLarData2021(db2021.Year2021Period.Q3)
        }

        val tsData = year match {
          case Period.y2018   => db2018.validationTSData2018
          case Period.y2019   => db2019.validationTSData2019
          case Period.y2020   => db2020.validationTSData2020(db2020.Year2020Period.Whole)
          case Period.y2020Q1 => db2020.validationTSData2020(db2020.Year2020Period.Q1)
          case Period.y2020Q2 => db2020.validationTSData2020(db2020.Year2020Period.Q2)
          case Period.y2020Q3 => db2020.validationTSData2020(db2020.Year2020Period.Q3)
          case Period.y2021Q1 => db2021.validationTSData2021(db2021.Year2021Period.Q1)
          case Period.y2021Q2 => db2021.validationTSData2021(db2021.Year2021Period.Q2)
          case Period.y2021Q3 => db2021.validationTSData2021(db2021.Year2021Period.Q3)
        }
        List(
          new TSLinesCheck(dbConfig, tsData, larData),
          new LeiCountCheck(dbConfig, tsData, larData, leiCheckErrorMargin)
        )
      case Scope.Public =>
        // there is no modified lar table for 2020 and so no chcecks will run for this year and scope
        val larDataOpt = year match {
          case Period.y2018 => Some(db2018.validationMLarData2018)
          case Period.y2019 => Some(db2019.validationMLarData2019)
          case Period.y2020 => Some(db2020.validationMLarData2020)
          case Period.y2020Q1 | Period.y2020Q2 | Period.y2020Q3 =>
            throw new IllegalArgumentException("year 2020 is not supported to public publishers at the moment")
          case Period.y2021Q1 | Period.y2021Q2 | Period.y2021Q3 =>
            throw new IllegalArgumentException("year 2021 is not supported to public publishers at the moment")
        }
        val tsData = year match {
          case Period.y2018 => db2018.validationTSData2018
          case Period.y2019 => db2019.validationTSData2019
          case Period.y2020 => db2020.validationTSData2020(db2020.Year2020Period.Whole)
          case Period.y2020Q1 | Period.y2020Q1 | Period.y2020Q3 =>
            throw new IllegalArgumentException("year 2020 is not supported to public publishers at the moment")
          case Period.y2021Q1 | Period.y2021Q1 | Period.y2021Q3 =>
            throw new IllegalArgumentException("year 2021 is not supported to public publishers at the moment")
        }
        larDataOpt
          .map(larData =>
            List(
              new TSLinesCheck(dbConfig, tsData, larData),
              new LeiCountCheck(dbConfig, tsData, larData, leiCheckErrorMargin)
            )
          )
          .getOrElse(List())
    }

  }

  private def validate(checks: List[ValidationCheck]): Future[ValidatedNel[String, Unit]] = {
    import cats.instances.future._
    import cats.instances.list._
    import cats.syntax.all._
    val resultsF: Future[List[Either[String, Unit]]]       = checks.traverse(c => c.check())
    val gathered: Future[ValidatedNel[String, List[Unit]]] = resultsF.map(_.traverse(_.toValidatedNel))
    gathered.map(_.map(_ => ()))
  }

}

object PublishingGuard {

  def create(
              dbCompontnents: PublisherComponent2018
                with PublisherComponent2019
                with PublisherComponent2020
                with PublisherComponent2021
            )(implicit as: ActorSystem): PublishingGuard = {
    import as.dispatcher
    val config      = ConfigFactory.load("application.conf")
    val msgReporter = new MattermostNotifier(config.getString("hmda.publisher.validation.reportingUrl"))
    val dbConfig    = DbConfiguration.dbConfig
    new PublishingGuard(dbCompontnents, dbCompontnents, dbCompontnents,dbCompontnents, msgReporter, dbConfig)
  }

  sealed trait Period
  object Period {
    sealed trait Quarter extends Period

    case object y2018   extends Period
    case object y2019   extends Period
    case object y2020   extends Period
    case object y2020Q1 extends Period with Quarter
    case object y2020Q2 extends Period with Quarter
    case object y2020Q3 extends Period with Quarter
    case object y2021Q1 extends Period with Quarter
    case object y2021Q2 extends Period with Quarter
    case object y2021Q3 extends Period with Quarter
  }

  sealed trait Scope
  object Scope {
    case object Public  extends Scope
    case object Private extends Scope
  }
}
// $COVERAGE-ON$