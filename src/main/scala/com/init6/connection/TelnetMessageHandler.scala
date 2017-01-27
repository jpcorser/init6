package com.init6.connection

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, Props}
import akka.io.Tcp.{Close, Received}
import akka.util.ByteString
import com.init6.Constants._
import com.init6.coders.binary.hash.BSHA1
import com.init6.db.DAO
import com.init6.{Config, Init6Actor}
import com.init6.channels._
import com.init6.coders.telnet.TelnetEncoder
import com.init6.users.{Add, JoinChannelFromConnection, TelnetProtocol, UsersUserAdded}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

sealed trait State
case object ExpectingUsername extends State
case object ExpectingPassword extends State
case object LoggedIn extends State
case object ExpectingAckOfLoginMessages extends State
case object StoreExtraData extends State

sealed trait Data
case object UnauthenticatedUser extends Data
case class UnauthenticatedUser(user: String, packetsToProcess: mutable.Buffer[ByteString] = mutable.Buffer.empty) extends Data
case class AuthenticatedUser(actor: ActorRef, user: User, packetsToProcess: mutable.Buffer[ByteString]) extends Data

/**
 * Created by filip on 9/19/15.
 */
object TelnetMessageReceiver {
  def apply(clientAddress: InetSocketAddress, connection: ActorRef) =
    Props(classOf[TelnetMessageReceiver], clientAddress, connection)
}

class TelnetMessageReceiver(clientAddress: InetSocketAddress, override val connection: ActorRef) extends ChatReceiver {

  override val handler = context.actorOf(TelnetMessageHandler(clientAddress, connection))
}

object TelnetMessageHandler {
  def apply(clientAddress: InetSocketAddress, connection: ActorRef) =
    Props(classOf[TelnetMessageHandler], clientAddress, connection)
}

class TelnetMessageHandler(clientAddress: InetSocketAddress, connection: ActorRef) extends Init6KeepAliveActor with FSM[State, Data] {

  startWith(ExpectingUsername, UnauthenticatedUser)
  context.watch(connection)

  def sendNull() = {
    connection ! WriteOut(TelnetEncoder(UserNull).get)
  }

  when (ExpectingUsername) {
    case Event(Received(data), _) =>
      goto(ExpectingPassword) using UnauthenticatedUser(data.utf8String)
  }

  when (ExpectingPassword) {
    case Event(Received(data), buffer: UnauthenticatedUser) =>
      DAO.getUser(buffer.user).fold({
        connection ! WriteOut(TelnetEncoder(TELNET_INCORRECT_USERNAME))
        stop()
      })(dbUser => {
        if (dbUser.closed) {
          connection ! WriteOut(TelnetEncoder(ACCOUNT_CLOSED(buffer.user, dbUser.closedReason)))
          stop()
        } else {
          if (BSHA1(data.toArray).sameElements(dbUser.passwordHash)) {
            val u = User(clientAddress.getAddress.getHostAddress, buffer.user, dbUser.flags | Flags.UDP, 0, client = "TAHC")
            usersActor ! Add(connection, u, TelnetProtocol)
            goto(StoreExtraData)
          } else {
            connection ! WriteOut(TelnetEncoder(TELNET_INCORRECT_PASSWORD))
            stop()
          }
        }
      })
  }

  when (StoreExtraData) {
    case Event(Received(data), buffer: UnauthenticatedUser) =>
      buffer.packetsToProcess += data
      stay()
    case Event(UsersUserAdded(actor, user), buffer: UnauthenticatedUser) =>
      val authenticatedUser = AuthenticatedUser(actor, user, buffer.packetsToProcess)
      handleLoggedIn(authenticatedUser)
      goto(ExpectingAckOfLoginMessages) using authenticatedUser
  }

  when (LoggedIn) {
    case Event(Received(data), buffer: AuthenticatedUser) =>
      keptAlive = 0
      buffer.actor ! Received(data)
      stay()
  }

  when (ExpectingAckOfLoginMessages) {
    case Event(WrittenOut, buffer: AuthenticatedUser) =>
      buffer.actor ! JoinChannelFromConnection("Chat")
      buffer.packetsToProcess.foreach(buffer.actor ! Received(_))
      goto(LoggedIn)
  }

  def handleLoggedIn(buffer: AuthenticatedUser) = {
    connection ! WriteOut(TelnetEncoder(TELNET_CONNECTED(clientAddress)))
    connection ! WriteOut(TelnetEncoder(UserName(buffer.user.name)).get)
    connection ! WriteOut(TelnetEncoder(UserInfoArray(Config().motd)).get)
  }
}
