package com.chollinger.bridgefour.kaladin.models

import com.chollinger.bridgefour.shared.models.Config.LeaderConfig
import com.comcast.ip4s.{Host, Port, SocketAddress}
import munit.CatsEffectSuite
class ConfigSuite extends CatsEffectSuite {

  import cats.effect.*
  test("URI parsing is safe") {
    for {
      cfg       <- Config.load[IO]()
      _          = assertEquals(cfg.self.uri().toString, "http://localhost:5555")
      socketAddr = SocketAddress[Host](Host.fromString(cfg.self.host).get, Port.fromInt(cfg.self.port).get)
      _          = assertEquals(socketAddr.toString, "localhost:5555")
    } yield ()
  }

  test("URI parsing works with compose-style URIs") {
    // This test is a bit tongue-in-cheek. Underscores in URLs aren't valid, but Uri.fromString accepts it
    // Can't use that for a request, though: The BlazeClient constructs
    // SocketAddress[Host](Host.fromString(host).get, Port.fromInt(port).get)
    // Under the hood via the comcast library, which is apparently more strict
    for {
      cfg <- Config.load[IO]()
      cfg2 = cfg.copy(self = LeaderConfig(schema = "http", host = "spren_01", port = 5555))
      _    = assertEquals(cfg2.self.uri().toString, "http://spren_01:5555")
      h    = Host.fromString(cfg2.self.host)
      p    = Port.fromInt(cfg2.self.port)
      _    = assertEquals(h, None)
      _    = assertEquals(p.get.toString, "5555")
    } yield ()
  }

}
