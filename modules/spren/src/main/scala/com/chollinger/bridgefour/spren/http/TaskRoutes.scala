package com.chollinger.bridgefour.spren.http

import cats.effect.Concurrent
import cats.syntax.all._
import com.chollinger.bridgefour.shared.http.Route
import com.chollinger.bridgefour.shared.models.Config.SprenConfig
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.Status.ExecutionStatus
import com.chollinger.bridgefour.shared.models.Task.AssignedTaskConfig
import com.chollinger.bridgefour.spren.programs.TaskExecutor
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.accumulatingJsonOf
import org.http4s.circe.jsonEncoderOf
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
case class TaskRoutes[F[_]: Concurrent](cfg: SprenConfig, executor: TaskExecutor[F])
    extends Http4sDsl[F]
    with Route[F] {

  protected val prefixPath: String = "/task"

  // TODO: centralize implicits in companions
  given EntityDecoder[F, AssignedTaskConfig]           = accumulatingJsonOf[F, AssignedTaskConfig]
  given EntityEncoder[F, Map[TaskId, ExecutionStatus]] = jsonEncoderOf[F, Map[TaskId, ExecutionStatus]]
  given EntityEncoder[F, ExecutionStatus]              = jsonEncoderOf[F, ExecutionStatus]
  protected def httpRoutes(): HttpRoutes[F] = {
    HttpRoutes.of[F] {
      // Reports the state of a specific slot
      case GET -> Root / "status" / IntVar(slotId) => Ok(executor.getStatus(slotId))
      // Starts a task
      case req @ POST -> Root / "start" =>
        Ok(for {
          tasks <- req.as[List[AssignedTaskConfig]]
          res   <- executor.start(tasks)
        } yield res)
      case PUT -> Root / "stop" / IntVar(taskId) => ???
    }
  }

  def routes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes()
  )

}
