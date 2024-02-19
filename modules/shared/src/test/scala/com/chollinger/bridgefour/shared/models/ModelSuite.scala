package com.chollinger.bridgefour.shared.models

import com.chollinger.bridgefour.shared.jobs.JobClass
import com.chollinger.bridgefour.shared.models.IDs.*
import com.chollinger.bridgefour.shared.models.Job.UserJobConfig
import com.chollinger.bridgefour.shared.models.Status
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.latestbit.circe.adt.codec.*
class ModelSuite extends CatsEffectSuite {

  val taskId   = 100
  val jobId    = 0
  val workerId = 200
  val slotId   = 0

  test("AssignedTaskConfig SerDe") {
    val task = AssignedTaskConfig(
      taskId = TaskIdTuple(taskId, jobId),
      slotId = SlotIdTuple(slotId, workerId),
      input = "sample",
      output = "out",
      jobClass = JobClass.SampleJob,
      userSettings = Map()
    )
    val s = task.asJson.noSpaces
    assertEquals(
      s,
      "{\"taskId\":{\"id\":100,\"jobId\":0},\"slotId\":{\"id\":0,\"workerId\":200},\"input\":\"sample\",\"output\":\"out\",\"jobClass\":{\"type\":\"SampleJob\"},\"userSettings\":{}}"
    )

    val tl = decode[List[AssignedTaskConfig]](
      """[
        |    {"taskId":{"id":100,"jobId":0},"slotId":{"id":0,"workerId":200},"input":"sample","output":"out","jobClass":{"type":"SampleJob"},"userSettings":{}}
        |]""".stripMargin
    ).toOption.get
    assertEquals(tl, List(task))
  }

  test("circe + ADT works w/ https://github.com/abdolence/circe-tagged-adt-codec-scala3") {
    assertEquals(ExecutionStatus.InProgress.asJson.noSpaces, "{\"type\":\"InProgress\"}")
  }

  test("UserJobConfig SerDe") {
    val cfg = UserJobConfig(
      name = "unit-test",
      jobClass = JobClass.SampleJob,
      input = "/tmp/in",
      output = "/tmp/out",
      userSettings = Map()
    )
    val asStr =
      "{\"name\":\"unit-test\",\"jobClass\":{\"type\":\"SampleJob\"},\"input\":\"/tmp/in\",\"output\":\"/tmp/out\",\"userSettings\":{}}"
    assertEquals(
      cfg.asJson.noSpaces,
      asStr
    )
    assertEquals(decode[UserJobConfig](asStr).toOption.get, cfg)
  }

}
