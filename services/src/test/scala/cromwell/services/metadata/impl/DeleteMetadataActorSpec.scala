package cromwell.services.metadata.impl

import java.time.OffsetDateTime

import java.util.UUID

import akka.actor.Props
import akka.testkit.{EventFilter, TestProbe}
import cromwell.core.{TestKitSuite, WorkflowId}
import cromwell.services.metadata.impl.DeleteMetadataActor.DeleteMetadataAction
import org.scalatest.FlatSpecLike

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class DeleteMetadataActorSpec extends TestKitSuite with FlatSpecLike {

  private val workflowId1 = UUID.randomUUID().toString
  private val workflowId2 = UUID.randomUUID().toString

  val probe = TestProbe()

  private val deleteMetadataActor = createTestDeletionActor()
  private val deleteMetadataActorFailingLookups = createTestDeletionActor(failLookups = true)
  private val deleteMetadataActorFailingDeletions = createTestDeletionActor(failDeletions = true)

  it should "lookup for workflows matching criteria and delete their metadata" in {
    EventFilter.info(pattern = s"Successfully deleted metadata for workflow *", occurrences = 2) intercept {
      probe.send(deleteMetadataActor, DeleteMetadataAction)
    }
  }

  it should "write error message to the log if lookup fails" in {
    EventFilter.error(start = "Cannot delete metadata: unable to query list of workflow ids for metadata deletion from metadata summary table.", occurrences = 1) intercept {
      probe.send(deleteMetadataActorFailingLookups, DeleteMetadataAction)
    }
  }

  it should "write error message to the log if deletion fails and continue processing next workflow from the list" in {
    // error message should appear twice
    EventFilter.error(pattern = s"Cannot delete metadata for workflow *", occurrences = 2) intercept {
      probe.send(deleteMetadataActorFailingDeletions, DeleteMetadataAction)
    }
  }

  private def createTestDeletionActor(failLookups: Boolean = false, failDeletions: Boolean = false) = {
    val deleteMetadataActor = system.actorOf(Props(new DeleteMetadataActor(1 minute) {
      override def queryRootWorkflowSummaryEntriesByArchiveStatusAndOlderThanTimestamp(archiveStatus: Option[String], thresholdTimestamp: OffsetDateTime)(implicit ec: ExecutionContext): Future[Seq[String]] = {
        if (failLookups) {
          Future.failed(new RuntimeException("Error occurred during lookup for metadata deletion candidates"))
        } else {
          Future.successful(Seq(workflowId1, workflowId2))
        }
      }

      override def deleteNonLabelMetadataEntriesForWorkflowAndUpdateArchiveStatus(rootWorkflowId: WorkflowId, newArchiveStatus: Option[String])(implicit ec: ExecutionContext): Future[Int] = {
        if (failDeletions) {
          Future.failed(new RuntimeException(s"Error occurred during metadata deletion for workflow $rootWorkflowId"))
        } else {
          Future.successful(1)
        }
      }
    }))
    deleteMetadataActor
  }
}


