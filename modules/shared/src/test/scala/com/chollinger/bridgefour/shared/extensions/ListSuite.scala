package com.chollinger.bridgefour.shared.extensions

import com.chollinger.bridgefour.shared.extensions.takeN
import munit.CatsEffectSuite

class ListSuite extends CatsEffectSuite {

  test("takeN") {
    assertEquals(List(1, 2, 3, 4, 5).takeN(2), (List(1, 2), List(3, 4, 5)))
    assertEquals(List(1, 2, 3, 4, 5).takeN(5), (List(1, 2, 3, 4, 5), List()))
    assertEquals(List(1, 2, 3, 4, 5).takeN(50), (List(1, 2, 3, 4, 5), List()))
    assertEquals(List(1, 2, 3, 4, 5).takeN(0), (List(), List(1, 2, 3, 4, 5)))
    assertEquals(List(1, 2, 3, 4, 5).takeN(-1), (List(), List(1, 2, 3, 4, 5)))
    assertEquals(List().takeN(0), (List(), List()))
    assertEquals(List().takeN(500), (List(), List()))
    assertEquals(List().takeN(-1), (List(), List()))
  }

}
