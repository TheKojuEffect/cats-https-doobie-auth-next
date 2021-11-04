package com.nepalius.auth.api

import cats.Monad
import cats.data.OptionT
import cats.effect.Concurrent
import cats.implicits._
import com.nepalius.auth.Auth.AuthHandler
import com.nepalius.user.domain.UserService
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.{jsonOf, _}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityDecoder, HttpRoutes, Response}
import tsec.authentication.{TSecAuthService, asAuthed}
import tsec.common.Verified
import tsec.passwordhashers.{PasswordHash, PasswordHasher}

object AuthRoutes {

  def routes[F[_]: Concurrent, A](
      userService: UserService[F],
      passwordHasher: PasswordHasher[F, A],
      authHandler: AuthHandler[F],
  ): HttpRoutes[F] = Router(
    "login" -> logIn(userService, passwordHasher, authHandler),
    "logout" -> logOut(authHandler),
    "current-user" -> currentUser(authHandler),
  )

  def logIn[F[_]: Concurrent, A](
      userService: UserService[F],
      passwordHasher: PasswordHasher[F, A],
      authHandler: AuthHandler[F],
  ): HttpRoutes[F] = {
    implicit val logInRequestDecoder: EntityDecoder[F, LogInRequest] = jsonOf
    val dsl = Http4sDsl[F]
    import dsl._

    HttpRoutes.of[F] { case req @ POST -> Root =>
      val action = for {
        logInRequest <- OptionT.liftF(req.as[LogInRequest])
        user <- userService.getUserByEmail(logInRequest.email)
        verificationStatus <- OptionT.liftF(
          passwordHasher.checkpw(logInRequest.password, PasswordHash[A](user.password)),
        )
        _ <- OptionT.fromOption[F](verificationStatus match {
          case Verified => Option(())
          case _        => None
        })
        token <- OptionT.liftF(authHandler.authenticator.create(user.id))
      } yield (user, token)

      action.value.flatMap {
        case Some((user, token)) =>
          Ok(user.id.asJson).map(authHandler.authenticator.embed(_, token))
        case None =>
          Forbidden("Invalid email or password.")
      }
    }
  }

  def logOut[F[_]: Monad](authHandler: AuthHandler[F]): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._
    authHandler.liftService(
      TSecAuthService { case req @ POST -> Root asAuthed _ =>
        Response[F]().removeCookie(req.authenticator.toCookie.copy(content = "")).pure[F]
      },
    )
  }

  def currentUser[F[_]: Monad](
      authHandler: AuthHandler[F],
  ): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl._

    authHandler.liftService(
      TSecAuthService { case GET -> Root asAuthed user =>
        Ok(user.asJson)
      },
    )
  }
}
