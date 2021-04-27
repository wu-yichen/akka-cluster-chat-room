package chatRoom

import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.util.Timeout
import chatRoom.ChatDomain.{ ChatMessage, EnterRoom, UserMessage }
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object ChatDomain {
  case class ChatMessage(name: String, contents: String)
  case class UserMessage(contents: String)
  case class EnterRoom(fullAddress: String, name: String)
}

class ChatActor(name: String, port: Int) extends Actor with ActorLogging {
  implicit val timeout: Timeout = Timeout(3 seconds)
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher

  val cluster: Cluster = Cluster(context.system)
  override def preStart(): Unit =
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
    )

  override def postStop(): Unit =
    cluster.unsubscribe(self)

  override def receive: Receive = execute(Map.empty[String, String])

  def execute(chatRoomMember: Map[String, String]): Receive = {
    case MemberUp(member) =>
      val actor = context.actorSelection(s"${member.address.toString}/user/chatActor")
      actor ! EnterRoom(s"${self.path.address}@localhost:$port", name)

    case MemberRemoved(member, _) =>
      log.info(s"${chatRoomMember(member.address.toString)} left the room !")
      context.become(execute(chatRoomMember - member.address.toString))

    case EnterRoom(address, newMember) =>
      if (name != newMember) {
        log.info(s"Hi $newMember welcome to the room !")
        context.become(execute(chatRoomMember + (address -> newMember)))
      }

    case UserMessage(content) =>
      chatRoomMember.keys.foreach { address =>
        val actor = context.actorSelection(s"$address/user/chatActor")
        log.info(actor.toString())
        actor ! ChatMessage(name, content)
      }

    case ChatMessage(name, content) =>
      log.info(s"$name said $content")
  }
}
object ChatActor {
  def props(name: String, port: Int): Props = Props(new ChatActor(name, port))
}
class ChatApp(name: String, port: Int) extends App {
  val config = ConfigFactory
    .parseString(
      s"""
      |akka.remote.artery.canonical.port = $port
      |""".stripMargin
    )
    .withFallback(ConfigFactory.load("chatRoom/chatRoom.conf"))

  val system = ActorSystem("MyClusterChatApp", config)
  val chatActor = system.actorOf(ChatActor.props(name, port), "chatActor")

  scala.io.Source.stdin.getLines().foreach { line =>
    chatActor ! UserMessage(line)
  }
}

object Person1 extends ChatApp("Person1", 2551)
object Person2 extends ChatApp("Person2", 2552)
object Person3 extends ChatApp("Person3", 2553)
