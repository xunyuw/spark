/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.ui

import javax.servlet.http.HttpServletRequest

import scala.xml._

import org.apache.commons.lang3.StringEscapeUtils

import org.apache.spark.streaming.Time
import org.apache.spark.streaming.ui.StreamingJobProgressListener.{OutputOpId, SparkJobId}
import org.apache.spark.ui.jobs.UIData.JobUIData
import org.apache.spark.ui.{UIUtils => SparkUIUtils, WebUIPage}

private[ui] case class SparkJobIdWithUIData(sparkJobId: SparkJobId, jobUIData: Option[JobUIData])

private[ui] class BatchPage(parent: StreamingTab) extends WebUIPage("batch") {
  private val streamingListener = parent.listener
  private val sparkListener = parent.ssc.sc.jobProgressListener

  private def columns: Seq[Node] = {
    <th>Output Op Id</th>
      <th>Description</th>
      <th>Duration</th>
      <th>Status</th>
      <th>Job Id</th>
      <th>Duration</th>
      <th class="sorttable_nosort">Stages: Succeeded/Total</th>
      <th class="sorttable_nosort">Tasks (for all stages): Succeeded/Total</th>
      <th>Error</th>
  }

  private def generateJobRow(
      outputOpId: OutputOpId,
      outputOpDescription: Seq[Node],
      formattedOutputOpDuration: String,
      outputOpStatus: String,
      numSparkJobRowsInOutputOp: Int,
      isFirstRow: Boolean,
      sparkJob: SparkJobIdWithUIData): Seq[Node] = {
    if (sparkJob.jobUIData.isDefined) {
      generateNormalJobRow(outputOpId, outputOpDescription, formattedOutputOpDuration,
        outputOpStatus, numSparkJobRowsInOutputOp, isFirstRow, sparkJob.jobUIData.get)
    } else {
      generateDroppedJobRow(outputOpId, outputOpDescription, formattedOutputOpDuration,
        outputOpStatus, numSparkJobRowsInOutputOp, isFirstRow, sparkJob.sparkJobId)
    }
  }

  private def generateOutputOpRowWithoutSparkJobs(
    outputOpId: OutputOpId,
    outputOpDescription: Seq[Node],
    formattedOutputOpDuration: String,
    outputOpStatus: String): Seq[Node] = {
    <tr>
      <td class="output-op-id-cell" >{outputOpId.toString}</td>
      <td>{outputOpDescription}</td>
      <td>{formattedOutputOpDuration}</td>
      {outputOpStatusCell(outputOpStatus, rowspan = 1)}
      <!-- Job Id -->
      <td>-</td>
      <!-- Duration -->
      <td>-</td>
      <!-- Stages: Succeeded/Total -->
      <td>-</td>
      <!-- Tasks (for all stages): Succeeded/Total -->
      <td>-</td>
      <!-- Error -->
      <td>-</td>
    </tr>
  }

  /**
   * Generate a row for a Spark Job. Because duplicated output op infos needs to be collapsed into
   * one cell, we use "rowspan" for the first row of a output op.
   */
  private def generateNormalJobRow(
      outputOpId: OutputOpId,
      outputOpDescription: Seq[Node],
      formattedOutputOpDuration: String,
      outputOpStatus: String,
      numSparkJobRowsInOutputOp: Int,
      isFirstRow: Boolean,
      sparkJob: JobUIData): Seq[Node] = {
    val duration: Option[Long] = {
      sparkJob.submissionTime.map { start =>
        val end = sparkJob.completionTime.getOrElse(System.currentTimeMillis())
        end - start
      }
    }
    val lastFailureReason =
      sparkJob.stageIds.sorted.reverse.flatMap(sparkListener.stageIdToInfo.get).
      dropWhile(_.failureReason == None).take(1). // get the first info that contains failure
      flatMap(info => info.failureReason).headOption.getOrElse("")
    val formattedDuration = duration.map(d => SparkUIUtils.formatDuration(d)).getOrElse("-")
    val detailUrl = s"${SparkUIUtils.prependBaseUri(parent.basePath)}/jobs/job?id=${sparkJob.jobId}"

    // In the first row, output op id and its information needs to be shown. In other rows, these
    // cells will be taken up due to "rowspan".
    // scalastyle:off
    val prefixCells =
      if (isFirstRow) {
        <td class="output-op-id-cell" rowspan={numSparkJobRowsInOutputOp.toString}>{outputOpId.toString}</td>
        <td rowspan={numSparkJobRowsInOutputOp.toString}>
          {outputOpDescription}
        </td>
        <td rowspan={numSparkJobRowsInOutputOp.toString}>{formattedOutputOpDuration}</td> ++
        {outputOpStatusCell(outputOpStatus, numSparkJobRowsInOutputOp)}
      } else {
        Nil
      }
    // scalastyle:on

    <tr>
      {prefixCells}
      <td sorttable_customkey={sparkJob.jobId.toString}>
        <a href={detailUrl}>
          {sparkJob.jobId}{sparkJob.jobGroup.map(id => s"($id)").getOrElse("")}
        </a>
      </td>
      <td sorttable_customkey={duration.getOrElse(Long.MaxValue).toString}>
        {formattedDuration}
      </td>
      <td class="stage-progress-cell">
        {sparkJob.completedStageIndices.size}/{sparkJob.stageIds.size - sparkJob.numSkippedStages}
        {if (sparkJob.numFailedStages > 0) s"(${sparkJob.numFailedStages} failed)"}
        {if (sparkJob.numSkippedStages > 0) s"(${sparkJob.numSkippedStages} skipped)"}
      </td>
      <td class="progress-cell">
        {
          SparkUIUtils.makeProgressBar(
            started = sparkJob.numActiveTasks,
            completed = sparkJob.numCompletedTasks,
            failed = sparkJob.numFailedTasks,
            skipped = sparkJob.numSkippedTasks,
            total = sparkJob.numTasks - sparkJob.numSkippedTasks)
        }
      </td>
      {failureReasonCell(lastFailureReason, rowspan = 1)}
    </tr>
  }

  /**
   * If a job is dropped by sparkListener due to exceeding the limitation, we only show the job id
   * with "-" cells.
   */
  private def generateDroppedJobRow(
      outputOpId: OutputOpId,
      outputOpDescription: Seq[Node],
      formattedOutputOpDuration: String,
      outputOpStatus: String,
      numSparkJobRowsInOutputOp: Int,
      isFirstRow: Boolean,
      jobId: Int): Seq[Node] = {
    // In the first row, output op id and its information needs to be shown. In other rows, these
    // cells will be taken up due to "rowspan".
    // scalastyle:off
    val prefixCells =
      if (isFirstRow) {
        <td class="output-op-id-cell" rowspan={numSparkJobRowsInOutputOp.toString}>{outputOpId.toString}</td>
          <td rowspan={numSparkJobRowsInOutputOp.toString}>{outputOpDescription}</td>
          <td rowspan={numSparkJobRowsInOutputOp.toString}>{formattedOutputOpDuration}</td> ++
          {outputOpStatusCell(outputOpStatus, numSparkJobRowsInOutputOp)}
      } else {
        Nil
      }
    // scalastyle:on

    <tr>
      {prefixCells}
      <td sorttable_customkey={jobId.toString}>
        {if (jobId >= 0) jobId.toString else "-"}
      </td>
      <!-- Duration -->
      <td>-</td>
      <!-- Stages: Succeeded/Total -->
      <td>-</td>
      <!-- Tasks (for all stages): Succeeded/Total -->
      <td>-</td>
      <!-- Error -->
      <td>-</td>
    </tr>
  }

  private def generateOutputOpIdRow(
      outputOpId: OutputOpId,
      outputOpStatus: String,
      sparkJobs: Seq[SparkJobIdWithUIData]): Seq[Node] = {
    // We don't count the durations of dropped jobs
    val sparkJobDurations = sparkJobs.filter(_.jobUIData.nonEmpty).map(_.jobUIData.get).
      map(sparkJob => {
        sparkJob.submissionTime.map { start =>
          val end = sparkJob.completionTime.getOrElse(System.currentTimeMillis())
          end - start
        }
      })
    val formattedOutputOpDuration =
      if (sparkJobDurations.isEmpty || sparkJobDurations.exists(_ == None)) {
        // If no job or any job does not finish, set "formattedOutputOpDuration" to "-"
        "-"
      } else {
        SparkUIUtils.formatDuration(sparkJobDurations.flatMap(x => x).sum)
      }

    val description = generateOutputOpDescription(sparkJobs)

    if (sparkJobs.isEmpty) {
      generateOutputOpRowWithoutSparkJobs(
        outputOpId, description, formattedOutputOpDuration, outputOpStatus)
    } else {
      val firstRow =
        generateJobRow(
          outputOpId,
          description,
          formattedOutputOpDuration,
          outputOpStatus,
          sparkJobs.size,
          true,
          sparkJobs.head)
      val tailRows =
        sparkJobs.tail.map { sparkJob =>
          generateJobRow(
            outputOpId,
            description,
            formattedOutputOpDuration,
            outputOpStatus,
            sparkJobs.size,
            false,
            sparkJob)
        }
      (firstRow ++ tailRows).flatten
    }
  }

  private def generateOutputOpDescription(sparkJobs: Seq[SparkJobIdWithUIData]): Seq[Node] = {
    val lastStageInfo =
      sparkJobs.flatMap(_.jobUIData).headOption. // Get the first JobUIData
        flatMap { sparkJob => // For the first job, get the latest Stage info
          if (sparkJob.stageIds.isEmpty) {
            None
          } else {
            sparkListener.stageIdToInfo.get(sparkJob.stageIds.max)
          }
        }
    lastStageInfo match {
      case Some(stageInfo) =>
        val details = if (stageInfo.details.nonEmpty) {
          <span
            onclick="this.parentNode.querySelector('.stage-details').classList.toggle('collapsed')"
            class="expand-details">
              +details
          </span> ++
          <div class="stage-details collapsed">
            <pre>{stageInfo.details}</pre>
          </div>
        } else {
          NodeSeq.Empty
        }

        <div> {stageInfo.name} {details} </div>
      case None =>
        Text("(Unknown)")
    }
  }

  private def failureReasonCell(
      failureReason: String,
      rowspan: Int,
      includeFirstLineInExpandDetails: Boolean = true): Seq[Node] = {
    val isMultiline = failureReason.indexOf('\n') >= 0
    // Display the first line by default
    val failureReasonSummary = StringEscapeUtils.escapeHtml4(
      if (isMultiline) {
        failureReason.substring(0, failureReason.indexOf('\n'))
      } else {
        failureReason
      })
    val failureDetails =
      if (isMultiline && !includeFirstLineInExpandDetails) {
        // Skip the first line
        failureReason.substring(failureReason.indexOf('\n') + 1)
      } else {
        failureReason
      }
    val details = if (isMultiline) {
      // scalastyle:off
      <span onclick="this.parentNode.querySelector('.stacktrace-details').classList.toggle('collapsed')"
            class="expand-details">
        +details
      </span> ++
        <div class="stacktrace-details collapsed">
          <pre>{failureDetails}</pre>
        </div>
      // scalastyle:on
    } else {
      ""
    }

    if (rowspan == 1) {
      <td valign="middle" style="max-width: 300px">{failureReasonSummary}{details}</td>
    } else {
      <td valign="middle" style="max-width: 300px" rowspan={rowspan.toString}>
        {failureReasonSummary}{details}
      </td>
    }
  }

  private def getJobData(sparkJobId: SparkJobId): Option[JobUIData] = {
    sparkListener.activeJobs.get(sparkJobId).orElse {
      sparkListener.completedJobs.find(_.jobId == sparkJobId).orElse {
        sparkListener.failedJobs.find(_.jobId == sparkJobId)
      }
    }
  }

  /**
   * Generate the job table for the batch.
   */
  private def generateJobTable(batchUIData: BatchUIData): Seq[Node] = {
    val outputOpIdToSparkJobIds = batchUIData.outputOpIdSparkJobIdPairs.groupBy(_.outputOpId).
      map { case (outputOpId, outputOpIdAndSparkJobIds) =>
        // sort SparkJobIds for each OutputOpId
        (outputOpId, outputOpIdAndSparkJobIds.map(_.sparkJobId).sorted)
      }
    val outputOps = (0 until batchUIData.numOutputOp).map { outputOpId =>
      val status = batchUIData.failureReason.get(outputOpId).map { failure =>
        if (failure.startsWith("org.apache.spark.SparkException")) {
          "Failed due to Spark job error\n" + failure
        } else {
          var nextLineIndex = failure.indexOf("\n")
          if (nextLineIndex < 0) {
            nextLineIndex = failure.size
          }
          val firstLine = failure.substring(0, nextLineIndex)
          s"Failed due to error: $firstLine\n$failure"
        }
      }.getOrElse("Succeeded")
      val sparkJobIds = outputOpIdToSparkJobIds.getOrElse(outputOpId, Seq.empty)
      (outputOpId, status, sparkJobIds)
    }
    sparkListener.synchronized {
      val outputOpIdWithJobs: Seq[(OutputOpId, String, Seq[SparkJobIdWithUIData])] =
        outputOps.map { case (outputOpId, status, sparkJobIds) =>
          (outputOpId, status,
            sparkJobIds.map(sparkJobId => SparkJobIdWithUIData(sparkJobId, getJobData(sparkJobId))))
        }

      <table id="batch-job-table" class="table table-bordered table-striped table-condensed">
        <thead>
          {columns}
        </thead>
        <tbody>
          {
            outputOpIdWithJobs.map {
              case (outputOpId, status, sparkJobIds) =>
                generateOutputOpIdRow(outputOpId, status, sparkJobIds)
            }
          }
        </tbody>
      </table>
    }
  }

  def render(request: HttpServletRequest): Seq[Node] = {
    val batchTime = Option(request.getParameter("id")).map(id => Time(id.toLong)).getOrElse {
      throw new IllegalArgumentException(s"Missing id parameter")
    }
    val formattedBatchTime =
      UIUtils.formatBatchTime(batchTime.milliseconds, streamingListener.batchDuration)

    val batchUIData = streamingListener.getBatchUIData(batchTime).getOrElse {
      throw new IllegalArgumentException(s"Batch $formattedBatchTime does not exist")
    }

    val formattedSchedulingDelay =
      batchUIData.schedulingDelay.map(SparkUIUtils.formatDuration).getOrElse("-")
    val formattedProcessingTime =
      batchUIData.processingDelay.map(SparkUIUtils.formatDuration).getOrElse("-")
    val formattedTotalDelay = batchUIData.totalDelay.map(SparkUIUtils.formatDuration).getOrElse("-")

    val inputMetadatas = batchUIData.streamIdToInputInfo.values.flatMap { inputInfo =>
      inputInfo.metadataDescription.map(desc => inputInfo.inputStreamId -> desc)
    }.toSeq
    val summary: NodeSeq =
      <div>
        <ul class="unstyled">
          <li>
            <strong>Batch Duration: </strong>
            {SparkUIUtils.formatDuration(streamingListener.batchDuration)}
          </li>
          <li>
            <strong>Input data size: </strong>
            {batchUIData.numRecords} records
          </li>
          <li>
            <strong>Scheduling delay: </strong>
            {formattedSchedulingDelay}
          </li>
          <li>
            <strong>Processing time: </strong>
            {formattedProcessingTime}
          </li>
          <li>
            <strong>Total delay: </strong>
            {formattedTotalDelay}
          </li>
          {
            if (inputMetadatas.nonEmpty) {
              <li>
                <strong>Input Metadata:</strong>{generateInputMetadataTable(inputMetadatas)}
              </li>
            }
          }
        </ul>
      </div>

    val jobTable =
      if (batchUIData.outputOpIdSparkJobIdPairs.isEmpty) {
        <div>Cannot find any job for Batch {formattedBatchTime}.</div>
      } else {
        generateJobTable(batchUIData)
      }

    val content = summary ++ jobTable

    SparkUIUtils.headerSparkPage(s"Details of batch at $formattedBatchTime", content, parent)
  }

  def generateInputMetadataTable(inputMetadatas: Seq[(Int, String)]): Seq[Node] = {
    <table class={SparkUIUtils.TABLE_CLASS_STRIPED}>
      <thead>
        <tr>
          <th>Input</th>
          <th>Metadata</th>
        </tr>
      </thead>
      <tbody>
        {inputMetadatas.flatMap(generateInputMetadataRow)}
      </tbody>
    </table>
  }

  def generateInputMetadataRow(inputMetadata: (Int, String)): Seq[Node] = {
    val streamId = inputMetadata._1

    <tr>
      <td>{streamingListener.streamName(streamId).getOrElse(s"Stream-$streamId")}</td>
      <td>{metadataDescriptionToHTML(inputMetadata._2)}</td>
    </tr>
  }

  private def metadataDescriptionToHTML(metadataDescription: String): Seq[Node] = {
    // tab to 4 spaces and "\n" to "<br/>"
    Unparsed(StringEscapeUtils.escapeHtml4(metadataDescription).
      replaceAllLiterally("\t", "&nbsp;&nbsp;&nbsp;&nbsp;").replaceAllLiterally("\n", "<br/>"))
  }

  private def outputOpStatusCell(status: String, rowspan: Int): Seq[Node] = {
    if (status == "Succeeded") {
      <td rowspan={rowspan.toString}>Succeeded</td>
    } else {
      failureReasonCell(status, rowspan, includeFirstLineInExpandDetails = false)
    }
  }
}
