package com.chollinger.bridgefour.shared.types

import cats.MonadError

object Typeclasses {
  type ThrowableMonadError[F[_]] = MonadError[F, Throwable]
}
