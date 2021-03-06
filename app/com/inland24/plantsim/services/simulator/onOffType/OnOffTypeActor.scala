/*
 * Copyright (c) 2017 joesan @ http://github.com/joesan
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.inland24.plantsim.services.simulator.onOffType

import akka.actor.{Actor, ActorLogging, Props}
import OnOffTypeActor._
import com.inland24.plantsim.core.PowerPlantEventObservable
import com.inland24.plantsim.core.SupervisorActor.TelemetrySignals
import com.inland24.plantsim.models.DispatchCommand.DispatchOnOffPowerPlant
import com.inland24.plantsim.models.PowerPlantConfig.OnOffTypeConfig
import com.inland24.plantsim.models.ReturnToNormalCommand

/**
  * The Actor instance responsible for [[com.inland24.plantsim.models.PowerPlantType.OnOffType]]
  * PowerPlant's. The operation of such PowerPlant's are governed by this Actor with all
  * possible state transitions. Additionally, any events or alerts that arise from the
  * operations of this PowerPlant is also emitted to the outside world by this Actor. Eventing
  * or Alerting happens via the outChannel that is configured in the [[Config]] that is
  * passed during this Actor initialization.
  *
  * @param config
  */
class OnOffTypeActor private (config: Config)
  extends Actor with ActorLogging {

  val cfg = config.cfg
  val out = config.outChannel

  private def evolve(stm: StateMachine) = {
    val (signals, newStm) = StateMachine.popEvents(stm)
    for (s <- signals) out.onNext(s)
    context.become(active(newStm))
  }

  /*
   * Initialize the PowerPlant
   */
  override def preStart(): Unit = {
    super.preStart()
    self ! InitMessage
  }

  override def receive: Receive = {
    case InitMessage =>
      evolve(StateMachine.init(StateMachine.empty(cfg), cfg.minPower))
  }

  /**
    * The PowerPlant is said to be active as soon as it is initialized. The
    * following state transitions are possible:
    *
    * DispatchOnOffPowerPlant - To TurnOn or TurnOff the PowerPlant
    *
    * ReturnToNormalCommand - This is yet another possibility to TurnOff this PowerPlant
    *
    * OutOfService - If for some reason this PowerPlant should be thrown out of operation
    *
    * ReturnToService - To make the PowerPlant operational again
    *
    * StateRequest - To get the current actual state of this PowerPlant
    *
    * TelemetrySignals - To get only the signals emitted by this PowerPlant
    *
    * @param state
    * @return
    */
  def active(state: StateMachine): Receive = {
    case TelemetrySignals =>
      sender ! state.signals

    case StateRequestMessage =>
      sender ! state

    case DispatchOnOffPowerPlant(_,_,_,turnOn) =>
      if (turnOn)
        evolve(StateMachine.turnOn(state, maxPower = cfg.maxPower))
      else // We could also ReturnToNormal using the DispatchOnOffPowerPlant command
        evolve(StateMachine.turnOff(state, minPower = cfg.minPower))

    case ReturnToNormalCommand => // ReturnToNormal means means returning to min power
      evolve(StateMachine.turnOff(state, minPower = cfg.minPower))

    case OutOfServiceMessage =>
      evolve(state.copy(signals = StateMachine.unAvailableSignals))

    case ReturnToServiceMessage =>
      context.become(receive)
      self ! InitMessage
  }
}
object OnOffTypeActor {

  case class Config(
    cfg: OnOffTypeConfig,
    outChannel: PowerPlantEventObservable
  )

  sealed trait Message
  case object InitMessage extends Message
  case object StateRequestMessage extends Message

  // These messages are meant for manually faulting and un-faulting the power plant
  case object OutOfServiceMessage extends Message
  case object ReturnToServiceMessage extends Message

  def props(cfg: Config): Props =
    Props(new OnOffTypeActor(cfg))
}